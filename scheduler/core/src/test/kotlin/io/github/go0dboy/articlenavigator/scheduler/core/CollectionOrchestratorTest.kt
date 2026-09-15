package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.RetryPolicy
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CollectionOrchestratorTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun successPersistsItemsCursorAndNextSchedule() = runTest {
        val source = source("one")
        val repos = Repositories(listOf(source))
        val item = DiscoveredItem(DiscoveredItemId("item"), source.id, "https://example.test/item", discoveredAt = now)
        val adapter = FakeAdapter(result = DiscoveryResult(listOf(item), SourceCursor(source.id, etag = "v2", lastCheckedAt = now)))

        val report = orchestrator(repos, adapter).run(CollectionRunContext(isUnmeteredNetwork = true))

        assertEquals(1, report.successes)
        assertEquals(listOf(item), repos.items)
        assertEquals("v2", repos.cursors[source.id]?.etag)
        assertEquals(now, repos.sources[source.id]?.lastSuccessfulCheckAt)
        assertEquals(now.plusSeconds(3600), repos.sources[source.id]?.nextCheckAt)
        assertEquals(0, repos.states[source.id]?.consecutiveFailures)
        assertEquals(1, repos.states[source.id]?.lastDiscoveredCount)
    }

    @Test
    fun failureBacksOffAndDoesNotBlockAnotherSource() = runTest {
        val bad = source("bad")
        val good = source("good")
        val repos = Repositories(listOf(bad, good))
        val adapter = PerSourceAdapter(bad.id)

        val report = orchestrator(repos, adapter).run(CollectionRunContext(isUnmeteredNetwork = true))

        assertEquals(1, report.failures)
        assertEquals(1, report.successes)
        assertEquals(1, repos.states[bad.id]?.consecutiveFailures)
        assertEquals(now.plusSeconds(5), repos.sources[bad.id]?.nextCheckAt)
        assertEquals(now.plusSeconds(3600), repos.sources[good.id]?.nextCheckAt)
    }

    @Test
    fun repeatedFailuresFallBackToNormalPollingAfterRetryBudget() = runTest {
        val source = source("bad")
        val repos = Repositories(listOf(source))
        repos.states[source.id] = SourceCollectionState(source.id, consecutiveFailures = 3)
        val adapter = FakeAdapter(error = IllegalArgumentException("boom"))

        orchestrator(repos, adapter).run(CollectionRunContext(isUnmeteredNetwork = true))

        assertEquals(4, repos.states[source.id]?.consecutiveFailures)
        assertEquals(now.plusSeconds(3600), repos.sources[source.id]?.nextCheckAt)
    }

    @Test
    fun unmeteredSourceIsSkippedWithoutMutatingScheduleOrState() = runTest {
        val source = source("wifi", unmetered = true)
        val repos = Repositories(listOf(source))
        val before = repos.sources[source.id]

        val report = orchestrator(repos, FakeAdapter()).run(CollectionRunContext(isUnmeteredNetwork = false))

        assertEquals(1, report.skipped)
        assertEquals(before, repos.sources[source.id])
        assertNull(repos.states[source.id])
    }

    @Test(expected = IllegalArgumentException::class)
    fun registryRejectsDuplicateAdapterKeys() {
        SourceAdapterRegistry(listOf(FakeAdapter(), FakeAdapter()))
    }

    @Test
    fun registryAllowsSameSourceTypeWithDifferentAdapterKeys() {
        val first = FakeAdapter(adapterType = "site-a")
        val second = FakeAdapter(adapterType = "site-b")
        val registry = SourceAdapterRegistry(listOf(first, second))

        assertSame(first, registry.resolve(source("a", adapterType = "site-a")))
        assertSame(second, registry.resolve(source("b", adapterType = "site-b")))
    }

    @Test
    fun incompatibleSourceTypeDoesNotResolveByAdapterKeyAlone() {
        val registry = SourceAdapterRegistry(listOf(FakeAdapter(adapterType = "rss-atom")))
        val incompatible = source("rest", adapterType = "rss-atom").copy(type = SourceType.REST_API)

        assertNull(registry.resolve(incompatible))
    }

    private fun orchestrator(repos: Repositories, adapter: SourceAdapter) = CollectionOrchestrator(
        sourceRepository = repos,
        ingestionRepository = repos,
        stateRepository = repos,
        adapterRegistry = SourceAdapterRegistry(listOf(adapter)),
        retryPolicy = RetryPolicy(maxAttempts = 4, initialDelay = Duration.ofSeconds(5), maxDelay = Duration.ofMinutes(1)),
        clock = clock,
        maxParallelism = 2,
    )

    private fun source(
        id: String,
        unmetered: Boolean = false,
        adapterType: String = "rss-atom",
    ) = Source(
        id = SourceId(id),
        name = id,
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1), requiresUnmeteredNetwork = unmetered),
        adapterType = adapterType,
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    private class FakeAdapter(
        private val result: DiscoveryResult? = null,
        private val error: Throwable? = null,
        override val adapterType: String = "rss-atom",
    ) : SourceAdapter {
        override val supportedTypes = setOf(SourceType.RSS)
        override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult {
            error?.let { throw it }
            return result ?: DiscoveryResult(emptyList(), SourceCursor(source.id, lastCheckedAt = Instant.EPOCH))
        }
        override suspend fun fetch(item: DiscoveredItem): FetchResult = error("not used")
    }

    private class PerSourceAdapter(private val failing: SourceId) : SourceAdapter {
        override val adapterType: String = "rss-atom"
        override val supportedTypes = setOf(SourceType.RSS)
        override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult {
            if (source.id == failing) error("source failure")
            return DiscoveryResult(emptyList(), SourceCursor(source.id, lastCheckedAt = Instant.EPOCH))
        }
        override suspend fun fetch(item: DiscoveredItem): FetchResult = error("not used")
    }

    private class Repositories(initialSources: List<Source>) : SourceRepository, IngestionRepository, CollectionStateRepository {
        val sources = initialSources.associateBy { it.id }.toMutableMap()
        val cursors = mutableMapOf<SourceId, SourceCursor>()
        val states = mutableMapOf<SourceId, SourceCollectionState>()
        val items = mutableListOf<DiscoveredItem>()
        private val raw = mutableMapOf<DiscoveredItemId, RawContent>()

        override suspend fun upsert(source: Source) { sources[source.id] = source }
        override suspend fun findById(id: SourceId): Source? = sources[id]
        override suspend fun findDue(now: Instant): List<Source> = sources.values.filter { it.enabled && (it.nextCheckAt == null || !it.nextCheckAt!!.isAfter(now)) }
        override suspend fun listAll(): List<Source> = sources.values.toList()
        override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = cursors[sourceId]
        override suspend fun saveCursor(cursor: SourceCursor) { cursors[cursor.sourceId] = cursor }
        override suspend fun load(sourceId: SourceId): SourceCollectionState? = states[sourceId]
        override suspend fun save(state: SourceCollectionState) { states[state.sourceId] = state }
        override suspend fun upsertDiscovered(item: DiscoveredItem) { items.removeAll { it.id == item.id }; items += item }
        override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = items.firstOrNull { it.id == id }
        override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? = items.firstOrNull { it.sourceId == sourceId && it.url == url }
        override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> = items
            .filter {
                it.status == DiscoveryStatus.DISCOVERED ||
                    (it.status == DiscoveryStatus.FAILED && (it.nextProcessingAt == null || !it.nextProcessingAt!!.isAfter(now)))
            }
            .take(limit)
        override suspend fun storeRawContent(content: RawContent) { raw[content.discoveredItemId] = content }
        override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = raw[id]
        override suspend fun deleteRawContent(id: DiscoveredItemId) { raw.remove(id) }
    }
}
