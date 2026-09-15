package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.RetryPolicy
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.CollectionStateRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class CollectionRunContext(val isUnmeteredNetwork: Boolean)

sealed interface SourceCollectionResult {
    val sourceId: SourceId

    data class Success(override val sourceId: SourceId, val discoveredCount: Int) : SourceCollectionResult
    data class Skipped(override val sourceId: SourceId, val reason: SkipReason) : SourceCollectionResult
    data class Failure(override val sourceId: SourceId, val errorType: String, val message: String?) : SourceCollectionResult
}

enum class SkipReason { REQUIRES_UNMETERED_NETWORK }

data class CollectionRunReport(val results: List<SourceCollectionResult>) {
    val successes: Int get() = results.count { it is SourceCollectionResult.Success }
    val failures: Int get() = results.count { it is SourceCollectionResult.Failure }
    val skipped: Int get() = results.count { it is SourceCollectionResult.Skipped }
}

/**
 * Resolves the concrete collector by the stable adapter key persisted on Source.
 * SourceType is then validated by the adapter itself. This allows many site-specific
 * adapters to share a broad SourceType without making the registry ambiguous.
 */
class SourceAdapterRegistry(adapters: List<SourceAdapter>) {
    private val byAdapterType: Map<String, SourceAdapter>

    init {
        require(adapters.all { it.adapterType.isNotBlank() }) { "Source adapter type must not be blank" }
        val duplicates = adapters.groupBy { it.adapterType }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Multiple adapters registered for: $duplicates" }
        byAdapterType = adapters.associateBy { it.adapterType }
    }

    fun resolve(source: Source): SourceAdapter? =
        byAdapterType[source.adapterType]?.takeIf { it.supports(source) }
}

class CollectionOrchestrator(
    private val sourceRepository: SourceRepository,
    private val ingestionRepository: IngestionRepository,
    private val stateRepository: CollectionStateRepository,
    private val adapterRegistry: SourceAdapterRegistry,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    maxParallelism: Int = 4,
) {
    private val semaphore = Semaphore(maxParallelism.also { require(it > 0) })

    suspend fun run(context: CollectionRunContext): CollectionRunReport = coroutineScope {
        val now = clock.instant()
        val results = sourceRepository.findDue(now)
            .map { source -> async { semaphore.withPermit { collectOne(source, context, now) } } }
            .awaitAll()
        CollectionRunReport(results)
    }

    private suspend fun collectOne(
        source: Source,
        context: CollectionRunContext,
        now: Instant,
    ): SourceCollectionResult {
        if (source.pollPolicy.requiresUnmeteredNetwork && !context.isUnmeteredNetwork) {
            return SourceCollectionResult.Skipped(source.id, SkipReason.REQUIRES_UNMETERED_NETWORK)
        }

        val existingState = stateRepository.load(source.id) ?: SourceCollectionState(source.id)
        val adapter = adapterRegistry.resolve(source)
            ?: return fail(
                source,
                existingState,
                now,
                IllegalStateException("No compatible adapter '${source.adapterType}' for ${source.type}"),
            )

        return try {
            val cursor = sourceRepository.loadCursor(source.id)
            val discovery = adapter.discover(source, cursor)
            var newItemCount = 0
            for (item in discovery.items) {
                val existing = ingestionRepository.findDiscovered(source.id, item.url)
                if (existing == null) {
                    ingestionRepository.upsertDiscovered(item)
                    newItemCount++
                } else {
                    ingestionRepository.upsertDiscovered(existing.mergeDiscoveryMetadata(item))
                }
            }
            sourceRepository.saveCursor(discovery.nextCursor)
            sourceRepository.upsert(
                source.copy(
                    lastSuccessfulCheckAt = now,
                    nextCheckAt = now.plus(source.pollPolicy.interval),
                ),
            )
            stateRepository.save(
                SourceCollectionState(
                    sourceId = source.id,
                    consecutiveFailures = 0,
                    lastAttemptAt = now,
                    lastDiscoveredCount = newItemCount,
                ),
            )
            SourceCollectionResult.Success(source.id, newItemCount)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(source, existingState, now, error)
        }
    }

    private fun DiscoveredItem.mergeDiscoveryMetadata(fresh: DiscoveredItem): DiscoveredItem = copy(
        canonicalUrl = fresh.canonicalUrl ?: canonicalUrl,
        title = fresh.title ?: title,
        publishedAt = fresh.publishedAt ?: publishedAt,
    )

    private suspend fun fail(
        source: Source,
        previous: SourceCollectionState,
        now: Instant,
        error: Throwable,
    ): SourceCollectionResult.Failure {
        val failures = previous.consecutiveFailures + 1
        val retryDelay = retryDelayAfterFailure(failures, source.pollPolicy.interval)
        val errorType = error::class.qualifiedName ?: error::class.simpleName ?: "Throwable"

        sourceRepository.upsert(source.copy(nextCheckAt = now.plus(retryDelay)))
        stateRepository.save(
            SourceCollectionState(
                sourceId = source.id,
                consecutiveFailures = failures,
                lastAttemptAt = now,
                lastErrorType = errorType,
                lastErrorMessage = error.message?.take(2_000),
                lastDiscoveredCount = previous.lastDiscoveredCount,
            ),
        )
        return SourceCollectionResult.Failure(source.id, errorType, error.message)
    }

    private fun retryDelayAfterFailure(failureCount: Int, normalInterval: Duration): Duration {
        if (failureCount >= retryPolicy.maxAttempts) return normalInterval
        return retryPolicy.delayBeforeAttempt(failureCount + 1)
    }
}
