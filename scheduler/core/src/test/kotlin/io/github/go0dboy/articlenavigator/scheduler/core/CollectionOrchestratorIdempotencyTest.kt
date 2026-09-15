package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.CollectionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceCollectionLease
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionOrchestratorIdempotencyTest {
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val source = Source(
        id = SourceId("source"),
        name = "source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    @Test
    fun repeatedDiscoveryDoesNotResetProcessedItem() = runTest {
        val existing = DiscoveredItem(
            id = DiscoveredItemId("stable"),
            sourceId = source.id,
            url = "https://example.test/article",
            canonicalUrl = "https://example.test/article",
            title = "Old title",
            discoveredAt = now.minusSeconds(600),
            lastSeenAt = now.minusSeconds(600),
            contentHash = "already-processed-hash",
            relevanceScore = 0.75,
            status = DiscoveryStatus.PROCESSED,
            processingAttempts = 2,
        )
        val fresh = existing.copy(
            title = "Updated feed title",
            resolvedUrl = "https://cdn.example.test/article",
            lastSeenAt = now,
            contentHash = null,
            relevanceScore = null,
            status = DiscoveryStatus.DISCOVERED,
            processingAttempts = 0,
        )
        val repositories = Repositories(existing)
        val adapter = object : SourceAdapter {
            override val adapterType = "rss-atom"
            override val supportedTypes = setOf(SourceType.RSS)
            override suspend fun discover(source: Source, cursor: SourceCursor?) =
                DiscoveryResult(listOf(fresh), SourceCursor(source.id, lastCheckedAt = now))
            override suspend fun fetch(item: DiscoveredItem): FetchResult = error("not used")
        }
        val orchestrator = CollectionOrchestrator(
            sourceRepository = repositories,
            collectionRepository = repositories,
            adapterRegistry = SourceAdapterRegistry(listOf(adapter)),
            clock = Clock.fixed(now, ZoneOffset.UTC),
            runTokenFactory = { "run-1" },
        )

        val report = orchestrator.run(CollectionRunContext(isUnmeteredNetwork = true))
        val stored = repositories.item

        assertEquals(1, (report.results.single() as SourceCollectionResult.Success).discoveredCount)
        assertEquals(DiscoveryStatus.PROCESSED, stored.status)
        assertEquals("already-processed-hash", stored.contentHash)
        assertEquals(0.75, stored.relevanceScore!!, 0.0)
        assertEquals(2, stored.processingAttempts)
        assertEquals(now.minusSeconds(600), stored.discoveredAt)
        assertEquals("Updated feed title", stored.title)
        assertEquals("https://cdn.example.test/article", stored.resolvedUrl)
        assertEquals(now, stored.lastSeenAt)
    }

    private inner class Repositories(initial: DiscoveredItem) : SourceRepository, CollectionRepository {
        var storedSource = source
        var item = initial
        var cursor: SourceCursor? = null
        var state: SourceCollectionState? = null
        var lease: SourceCollectionLease? = null

        override suspend fun upsert(source: Source) { storedSource = source }
        override suspend fun findById(id: SourceId): Source? = storedSource.takeIf { it.id == id }
        override suspend fun findDue(now: Instant): List<Source> = listOf(storedSource)
        override suspend fun listAll(): List<Source> = listOf(storedSource)
        override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = cursor
        override suspend fun saveCursor(cursor: SourceCursor) { this.cursor = cursor }
        override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean {
            if (storedSource.id != sourceId) return false
            storedSource = storedSource.copy(nextCheckAt = at)
            return true
        }

        override suspend fun tryClaim(
            sourceId: SourceId,
            runToken: String,
            now: Instant,
            leaseExpiresAt: Instant,
        ): SourceCollectionLease? {
            if (sourceId != storedSource.id) return null
            lease?.takeIf { it.expiresAt.isAfter(now) }?.let { return null }
            return SourceCollectionLease(
                source = storedSource,
                cursor = cursor,
                previousState = state,
                runToken = runToken,
                settingsRevision = storedSource.settingsRevision,
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
            if (!owns(lease)) return CollectionCommitOutcome.STALE
            items.firstOrNull { it.id == item.id }?.let { incoming ->
                item = item.copy(
                    canonicalUrl = incoming.canonicalUrl,
                    resolvedUrl = incoming.resolvedUrl,
                    title = incoming.title,
                    publishedAt = incoming.publishedAt,
                    lastSeenAt = incoming.lastSeenAt,
                )
            }
            this.cursor = cursor
            state = SourceCollectionState(
                sourceId = storedSource.id,
                consecutiveFailures = 0,
                lastAttemptAt = completedAt,
                lastDiscoveredCount = discoveredCount,
            )
            storedSource = storedSource.copy(
                lastSuccessfulCheckAt = completedAt,
                nextCheckAt = nextCheckAt,
            )
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
            if (!owns(lease)) return CollectionCommitOutcome.STALE
            this.lease = null
            return CollectionCommitOutcome.APPLIED
        }

        override suspend fun release(lease: SourceCollectionLease) {
            if (this.lease?.runToken == lease.runToken) this.lease = null
        }

        private fun owns(candidate: SourceCollectionLease): Boolean =
            lease?.runToken == candidate.runToken && storedSource.settingsRevision == candidate.settingsRevision
    }
}
