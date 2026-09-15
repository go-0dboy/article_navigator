package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.RetryPolicy
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.collector.api.SourceCollectionException
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.CollectionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceCollectionLease
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CollectionRetrySemanticsTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val source = Source(
        id = SourceId("source"),
        name = "Source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    @Test
    fun serverRetryAfterWinsWhenLongerThanLocalBackoff() = runTest {
        val repositories = MemoryRepositories(source)
        val error = SourceCollectionException(
            message = "HTTP 429",
            retryable = true,
            retryAfter = Duration.ofSeconds(120),
        )

        val report = orchestrator(repositories, ThrowingAdapter(error)).run(CollectionRunContext(true))

        assertEquals(1, report.failures)
        assertEquals(now.plusSeconds(120), repositories.source.nextCheckAt)
        assertEquals(1, repositories.state?.consecutiveFailures)
    }

    @Test
    fun permanentHttpClassificationUsesNormalPollIntervalNotAggressiveRetry() = runTest {
        val repositories = MemoryRepositories(source)
        val error = SourceCollectionException("HTTP 404", retryable = false)

        orchestrator(repositories, ThrowingAdapter(error)).run(CollectionRunContext(true))

        assertEquals(now.plusSeconds(3600), repositories.source.nextCheckAt)
    }

    @Test
    fun transportTimeoutUsesTransientLocalBackoff() = runTest {
        val repositories = MemoryRepositories(source)

        orchestrator(repositories, ThrowingAdapter(SocketTimeoutException("timed out")))
            .run(CollectionRunContext(true))

        assertEquals(now.plusSeconds(5), repositories.source.nextCheckAt)
        assertEquals(1, repositories.state?.consecutiveFailures)
    }

    @Test
    fun cancellationReleasesLeaseAndIsNotRecordedAsSourceFailure() = runTest {
        val repositories = MemoryRepositories(source)
        val orchestrator = orchestrator(repositories, ThrowingAdapter(CancellationException("stop")))

        try {
            orchestrator.run(CollectionRunContext(true))
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertNull(repositories.state)
        assertEquals(source.nextCheckAt, repositories.source.nextCheckAt)
        val replacement = repositories.tryClaim(
            source.id,
            runToken = "replacement",
            now = now,
            leaseExpiresAt = now.plusSeconds(60),
        )
        assertNotNull(replacement)
    }

    private fun orchestrator(repositories: MemoryRepositories, adapter: SourceAdapter) = CollectionOrchestrator(
        sourceRepository = repositories,
        collectionRepository = repositories,
        adapterRegistry = SourceAdapterRegistry(listOf(adapter)),
        retryPolicy = RetryPolicy(
            maxAttempts = 4,
            initialDelay = Duration.ofSeconds(5),
            maxDelay = Duration.ofMinutes(5),
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        runTokenFactory = { "run" },
    )

    private class ThrowingAdapter(private val error: Exception) : SourceAdapter {
        override val adapterType: String = "test"
        override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS)
        override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = throw error
        override suspend fun fetch(item: DiscoveredItem): FetchResult = error("not used")
    }

    private class MemoryRepositories(initial: Source) : SourceRepository, CollectionRepository {
        var source: Source = initial
        var state: SourceCollectionState? = null
        private var cursor: SourceCursor? = null
        private var lease: SourceCollectionLease? = null

        override suspend fun upsert(source: Source) {
            this.source = source
        }

        override suspend fun findById(id: SourceId): Source? = source.takeIf { it.id == id }

        override suspend fun findDue(now: Instant): List<Source> =
            if (source.enabled && (source.nextCheckAt == null || !source.nextCheckAt!!.isAfter(now))) listOf(source) else emptyList()

        override suspend fun listAll(): List<Source> = listOf(source)
        override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = cursor
        override suspend fun saveCursor(cursor: SourceCursor) {
            this.cursor = cursor
        }

        override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean {
            if (source.id != sourceId) return false
            source = source.copy(nextCheckAt = at)
            return true
        }

        override suspend fun tryClaim(
            sourceId: SourceId,
            runToken: String,
            now: Instant,
            leaseExpiresAt: Instant,
        ): SourceCollectionLease? {
            if (source.id != sourceId || !source.enabled) return null
            val current = lease
            if (current != null && current.expiresAt.isAfter(now)) return null
            return SourceCollectionLease(
                source = source,
                cursor = cursor,
                previousState = state,
                runToken = runToken,
                settingsRevision = source.settingsRevision,
                expiresAt = leaseExpiresAt,
            ).also { lease = it }
        }

        override suspend fun commitSuccess(
            lease: SourceCollectionLease,
            items: List<DiscoveredItem>,
            cursor: SourceCursor,
            completedAt: Instant,
            nextCheckAt: Instant,
            discoveredCount: Int,
        ): CollectionCommitOutcome {
            if (!owns(lease, completedAt)) return CollectionCommitOutcome.STALE
            this.cursor = cursor
            state = SourceCollectionState(
                sourceId = source.id,
                lastAttemptAt = completedAt,
                lastDiscoveredCount = discoveredCount,
            )
            source = source.copy(lastSuccessfulCheckAt = completedAt, nextCheckAt = nextCheckAt)
            this.lease = null
            return CollectionCommitOutcome.APPLIED
        }

        override suspend fun commitFailure(
            lease: SourceCollectionLease,
            completedAt: Instant,
            nextCheckAt: Instant,
            errorType: String,
            errorMessage: String?,
        ): CollectionCommitOutcome {
            if (!owns(lease, completedAt)) return CollectionCommitOutcome.STALE
            state = SourceCollectionState(
                sourceId = source.id,
                consecutiveFailures = (lease.previousState?.consecutiveFailures ?: 0) + 1,
                lastAttemptAt = completedAt,
                lastErrorType = errorType,
                lastErrorMessage = errorMessage,
            )
            source = source.copy(nextCheckAt = nextCheckAt)
            this.lease = null
            return CollectionCommitOutcome.APPLIED
        }

        override suspend fun release(lease: SourceCollectionLease) {
            if (this.lease?.runToken == lease.runToken) this.lease = null
        }

        private fun owns(candidate: SourceCollectionLease, at: Instant): Boolean {
            val current = lease ?: return false
            return current.runToken == candidate.runToken &&
                source.settingsRevision == candidate.settingsRevision &&
                current.expiresAt.isAfter(at)
        }
    }
}
