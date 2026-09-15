package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.CollectionStateRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
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
            contentHash = "already-processed-hash",
            status = DiscoveryStatus.PROCESSED,
            processingAttempts = 2,
        )
        val fresh = existing.copy(
            title = "Updated feed title",
            contentHash = null,
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
            ingestionRepository = repositories,
            stateRepository = repositories,
            adapterRegistry = SourceAdapterRegistry(listOf(adapter)),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val report = orchestrator.run(CollectionRunContext(isUnmeteredNetwork = true))
        val stored = repositories.item

        assertEquals(0, (report.results.single() as SourceCollectionResult.Success).discoveredCount)
        assertEquals(DiscoveryStatus.PROCESSED, stored.status)
        assertEquals("already-processed-hash", stored.contentHash)
        assertEquals(2, stored.processingAttempts)
        assertEquals("Updated feed title", stored.title)
    }

    private inner class Repositories(initial: DiscoveredItem) : SourceRepository, IngestionRepository, CollectionStateRepository {
        var storedSource = source
        var item = initial
        var cursor: SourceCursor? = null
        var state: SourceCollectionState? = null

        override suspend fun upsert(source: Source) { storedSource = source }
        override suspend fun findById(id: SourceId): Source? = storedSource.takeIf { it.id == id }
        override suspend fun findDue(now: Instant): List<Source> = listOf(storedSource)
        override suspend fun listAll(): List<Source> = listOf(storedSource)
        override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = cursor
        override suspend fun saveCursor(cursor: SourceCursor) { this.cursor = cursor }
        override suspend fun load(sourceId: SourceId): SourceCollectionState? = state
        override suspend fun save(state: SourceCollectionState) { this.state = state }
        override suspend fun upsertDiscovered(item: DiscoveredItem) { this.item = item }
        override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = item.takeIf { it.id == id }
        override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? =
            item.takeIf { it.sourceId == sourceId && it.url == url }
        override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> = emptyList()
        override suspend fun storeRawContent(content: RawContent) = Unit
        override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = null
        override suspend fun deleteRawContent(id: DiscoveredItemId) = Unit
    }
}
