package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.RetryPolicy
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.collector.api.SourceCollectionException
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.CollectionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceCollectionLease
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class CollectionRunContext(
    val isUnmeteredNetwork: Boolean,
    /** Optional absolute pass deadline shared with downstream ingestion. */
    val deadline: Instant? = null,
)

sealed interface SourceCollectionResult {
    val sourceId: SourceId
    data class Success(override val sourceId: SourceId, val discoveredCount: Int) : SourceCollectionResult
    data class Skipped(override val sourceId: SourceId, val reason: SkipReason) : SourceCollectionResult
    data class Failure(override val sourceId: SourceId, val errorType: String, val message: String?) : SourceCollectionResult
}

enum class SkipReason { REQUIRES_UNMETERED_NETWORK, ALREADY_CLAIMED, STALE_RESULT }

data class CollectionRunReport(
    val results: List<SourceCollectionResult>,
    val budgetExhausted: Boolean = false,
) {
    val successes: Int get() = results.count { it is SourceCollectionResult.Success }
    val failures: Int get() = results.count { it is SourceCollectionResult.Failure }
    val skipped: Int get() = results.count { it is SourceCollectionResult.Skipped }
    val discoveredEntries: Int get() = results.filterIsInstance<SourceCollectionResult.Success>().sumOf { it.discoveredCount }
}

class SourceAdapterRegistry(adapters: List<SourceAdapter>) {
    private val byAdapterType: Map<String, SourceAdapter>
    init {
        require(adapters.all { it.adapterType.isNotBlank() }) { "Source adapter type must not be blank" }
        val duplicates = adapters.groupBy { it.adapterType }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Multiple adapters registered for: $duplicates" }
        byAdapterType = adapters.associateBy { it.adapterType }
    }
    fun resolve(source: Source): SourceAdapter? = byAdapterType[source.adapterType]?.takeIf { it.supports(source) }
}

/**
 * Source execution is protected by an expiring persisted lease. Network I/O occurs outside the
 * DB transaction. A result is committed only if runToken and settingsRevision still match.
 */
class CollectionOrchestrator(
    private val sourceRepository: SourceRepository,
    private val collectionRepository: CollectionRepository,
    private val adapterRegistry: SourceAdapterRegistry,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val maxParallelism: Int = 4,
    private val maxSourcesPerRun: Int = 64,
    private val leaseDuration: Duration = Duration.ofMinutes(12),
    private val maxRunDuration: Duration = Duration.ofMinutes(8),
    private val runTokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(maxParallelism > 0)
        require(maxSourcesPerRun > 0)
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(!maxRunDuration.isNegative && !maxRunDuration.isZero)
        require(leaseDuration > maxRunDuration) { "Lease must outlive one orchestrator pass" }
    }

    suspend fun run(context: CollectionRunContext): CollectionRunReport = coroutineScope {
        val startedAt = clock.instant()
        val localDeadline = startedAt.plus(maxRunDuration)
        val deadline = context.deadline?.takeIf { it < localDeadline } ?: localDeadline

        // Network eligibility is deliberately applied before maxSourcesPerRun. Otherwise a queue
        // headed by unmetered-only sources could permanently starve later metered-eligible sources.
        val allDue = sourceRepository.findDue(startedAt)
        val blocked = if (context.isUnmeteredNetwork) {
            emptyList()
        } else {
            allDue.filter { it.pollPolicy.requiresUnmeteredNetwork }
        }
        val due = allDue.asSequence()
            .filter { context.isUnmeteredNetwork || !it.pollPolicy.requiresUnmeteredNetwork }
            .take(maxSourcesPerRun)
            .toList()

        val results = blocked.mapTo(mutableListOf<SourceCollectionResult>()) {
            SourceCollectionResult.Skipped(it.id, SkipReason.REQUIRES_UNMETERED_NETWORK)
        }
        var processedEligible = 0
        var budgetExhausted = false
        for (batch in due.chunked(maxParallelism)) {
            if (!clock.instant().isBefore(deadline)) {
                budgetExhausted = processedEligible < due.size
                break
            }
            val batchResults = batch.map { source -> async { collectOne(source, context) } }.awaitAll()
            results += batchResults
            processedEligible += batch.size
        }
        if (!budgetExhausted && processedEligible < due.size) budgetExhausted = true
        CollectionRunReport(results, budgetExhausted)
    }

    private suspend fun collectOne(candidate: Source, context: CollectionRunContext): SourceCollectionResult {
        if (candidate.pollPolicy.requiresUnmeteredNetwork && !context.isUnmeteredNetwork) {
            return SourceCollectionResult.Skipped(candidate.id, SkipReason.REQUIRES_UNMETERED_NETWORK)
        }
        val claimedAt = clock.instant()
        val lease = collectionRepository.tryClaim(
            sourceId = candidate.id,
            runToken = runTokenFactory(),
            now = claimedAt,
            leaseExpiresAt = claimedAt.plus(leaseDuration),
        ) ?: return SourceCollectionResult.Skipped(candidate.id, SkipReason.ALREADY_CLAIMED)

        try {
            val adapter = adapterRegistry.resolve(lease.source)
                ?: return recordSourceFailure(
                    lease,
                    SourceCollectionException(
                        "No compatible adapter '${lease.source.adapterType}' for ${lease.source.type}",
                        retryable = false,
                    ),
                )
            val discovery = try {
                adapter.discover(lease.source, lease.cursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return recordSourceFailure(lease, error)
            }

            val completedAt = clock.instant()
            return when (
                collectionRepository.commitSuccess(
                    lease = lease,
                    items = discovery.items,
                    cursor = discovery.nextCursor,
                    completedAt = completedAt,
                    nextCheckAt = completedAt.plus(lease.source.pollPolicy.interval),
                    discoveredCount = discovery.items.size,
                )
            ) {
                CollectionCommitOutcome.APPLIED -> SourceCollectionResult.Success(lease.source.id, discovery.items.size)
                CollectionCommitOutcome.STALE -> staleResult(lease)
            }
        } catch (cancelled: CancellationException) {
            releaseBestEffort(lease)
            throw cancelled
        }
    }

    private suspend fun recordSourceFailure(lease: SourceCollectionLease, error: Exception): SourceCollectionResult {
        val completedAt = clock.instant()
        val failures = (lease.previousState?.consecutiveFailures ?: 0) + 1
        val retryDelay = retryDelayAfterFailure(error, failures, lease.source.pollPolicy.interval)
        val errorType = error::class.qualifiedName ?: error::class.simpleName ?: "Exception"
        return when (
            collectionRepository.commitFailure(
                lease = lease,
                completedAt = completedAt,
                nextCheckAt = completedAt.plus(retryDelay),
                errorType = errorType,
                errorMessage = error.message,
            )
        ) {
            CollectionCommitOutcome.APPLIED -> SourceCollectionResult.Failure(lease.source.id, errorType, error.message)
            CollectionCommitOutcome.STALE -> staleResult(lease)
        }
    }

    private suspend fun staleResult(lease: SourceCollectionLease): SourceCollectionResult {
        releaseBestEffort(lease)
        return SourceCollectionResult.Skipped(lease.source.id, SkipReason.STALE_RESULT)
    }

    private suspend fun releaseBestEffort(lease: SourceCollectionLease) {
        withContext(NonCancellable) { runCatching { collectionRepository.release(lease) } }
    }

    private fun retryDelayAfterFailure(error: Exception, failureCount: Int, normalInterval: Duration): Duration {
        val classified = error as? SourceCollectionException
        if (classified?.retryable == false) return normalInterval
        val policyDelay = if (failureCount >= retryPolicy.maxAttempts) {
            normalInterval
        } else {
            retryPolicy.delayBeforeAttempt(failureCount + 1)
        }
        val serverDelay = classified?.retryAfter
        return if (serverDelay != null && serverDelay > policyDelay) serverDelay else policyDelay
    }
}
