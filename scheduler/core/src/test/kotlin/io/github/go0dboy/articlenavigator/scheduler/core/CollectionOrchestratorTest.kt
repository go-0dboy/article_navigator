package io.github.go0dboy.articlenavigator.scheduler.core

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.RetryPolicy
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.CollectionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceCollectionLease
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun unmeteredSourcesDoNotConsumeMeteredSourceLimit() = runTest {
        val wifiA = source("wifi-a", unmetered = true)
        val wifiB = source("wifi-b", unmetered = true)
        val metered = source("metered")
        val repos = Repositories(listOf(wifiA, wifiB, metered))

        val report = orchestrator(repos, FakeAdapter(), maxSourcesPerRun = 1)
            .run(CollectionRunContext(isUnmeteredNetwork = false))

        assertEquals(1, report.successes)
        assertEquals(2, report.skipped)
        assertEquals(now.plusSeconds(3600), repos.sources[metered.id]?.nextCheckAt)
        assertEquals(now, repos.sources[wifiA.id]?.nextCheckAt)
        assertEquals(now, repos.sources[wifiB.id]?.nextCheckAt)
        assertFalse(report.budgetExhausted)
    }

    @Test
    fun sourceCapRequestsContinuationAndLeavesRemainingSourceDue() = runTest {
        val first = source("first")
        val second = source("second")
        val third = source("third")
        val repos = Repositories(listOf(first, second, third))

        val report = orchestrator(repos, FakeAdapter(), maxSourcesPerRun = 2)
            .run(CollectionRunContext(isUnmeteredNetwork = true))

        assertEquals(2, report.successes)
        assertTrue(report.budgetExhausted)
        assertEquals(listOf(third.id), repos.findDue(now).map { it.id })
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

    private fun orchestrator(
        repos: Repositories,
        adapter: SourceAdapter,
        maxSourcesPerRun: Int = 64,
    ) = CollectionOrchestrator(
        sourceRepository = repos,
        collectionRepository = repos,
        adapterRegistry = SourceAdapterRegistry(listOf(adapter)),
        retryPolicy = RetryPolicy(maxAttempts = 4, initialDelay = Duration.ofSeconds(5), maxDelay = Duration.ofMinutes(1)),
        clock = clock,
        maxParallelism = 2,
        maxSourcesPerRun = maxSourcesPerRun,
        runTokenFactory = { "test-token-${repos.nextToken++}" },
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

    private class Repositories(initialSources: List<Source>) : SourceRepository, CollectionRepository {
        val sources = initialSources.associateBy { it.id }.toMutableMap()
        val cursors = mutableMapOf<SourceId, SourceCursor>()
        val states = mutableMapOf<SourceId, SourceCollectionState>()
        val items = mutableListOf<DiscoveredItem>()
        private val leases = mutableMapOf<SourceId, SourceCollectionLease>()
        var nextToken: Int = 0

        override suspend fun upsert(source: Source) { sources[source.id] = source }
        override suspend fun findById(id: SourceId): Source? = sources[id]
        override suspend fun findDue(now: Instant): List<Source> = sources.values.filter { source ->
            val lease = leases[source.id]
            source.enabled &&
                (source.nextCheckAt == null || !source.nextCheckAt!!.isAfter(now)) &&
                (lease == null || !lease.expiresAt.isAfter(now))
        }
        override suspend fun listAll(): List<Source> = sources.values.toList()
        override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = cursors[sourceId]
        override suspend fun saveCursor(cursor: SourceCursor) { cursors[cursor.sourceId] = cursor }
        override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean {
            val source = sources[sourceId] ?: return false
            sources[sourceId] = source.copy(nextCheckAt = at)
            return true
        }

        override suspend fun tryClaim(
            sourceId: SourceId,
            runToken: String,
            now: Instant,
            leaseExpiresAt: Instant,
        ): SourceCollectionLease? {
            val source = sources[sourceId]?.takeIf { it.enabled } ?: return null
            val current = leases[sourceId]
            if (current != null && current.expiresAt.isAfter(now)) return null
            return SourceCollectionLease(
                source = source,
                cursor = cursors[sourceId],
                previousState = states[sourceId],
                runToken = runToken,
                settingsRevision = source.settingsRevision,
                expiresAt = leaseExpiresAt,
            ).also { leases[sourceId] = it }
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
            items.forEach { incoming ->
                val index = this.items.indexOfFirst { it.id == incoming.id }
                if (index < 0) {
                    this.items += incoming
                } else {
                    val existing = this.items[index]
                    this.items[index] = existing.copy(
                        canonicalUrl = incoming.canonicalUrl,
                        resolvedUrl = incoming.resolvedUrl,
                        title = incoming.title,
                        publishedAt = incoming.publishedAt,
                        lastSeenAt = incoming.lastSeenAt,
                    )
                }
            }
            cursors[lease.source.id] = cursor
            states[lease.source.id] = SourceCollectionState(
                sourceId = lease.source.id,
                consecutiveFailures = 0,
                lastAttemptAt = completedAt,
                lastDiscoveredCount = discoveredCount,
            )
            sources[lease.source.id] = sources.getValue(lease.source.id).copy(
                lastSuccessfulCheckAt = completedAt,
                nextCheckAt = nextCheckAt,
            )
            leases.remove(lease.source.id)
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
            val failures = (lease.previousState?.consecutiveFailures ?: 0) + 1
            states[lease.source.id] = SourceCollectionState(
                sourceId = lease.source.id,
                consecutiveFailures = failures,
                lastAttemptAt = completedAt,
                lastErrorType = errorType,
                lastErrorMessage = errorMessage,
                lastDiscoveredCount = lease.previousState?.lastDiscoveredCount ?: 0,
            )
            sources[lease.source.id] = sources.getValue(lease.source.id).copy(nextCheckAt = nextCheckAt)
            leases.remove(lease.source.id)
            return CollectionCommitOutcome.APPLIED
        }

        override suspend fun release(lease: SourceCollectionLease) {
            if (leases[lease.source.id]?.runToken == lease.runToken) leases.remove(lease.source.id)
        }

        private fun owns(lease: SourceCollectionLease): Boolean {
            val current = leases[lease.source.id] ?: return false
            val source = sources[lease.source.id] ?: return false
            return current.runToken == lease.runToken && source.settingsRevision == lease.settingsRevision
        }
    }
}
