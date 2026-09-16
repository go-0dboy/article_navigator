package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionOrchestrator
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunContext
import io.github.go0dboy.articlenavigator.scheduler.core.SkipReason
import io.github.go0dboy.articlenavigator.scheduler.core.SourceAdapterRegistry
import io.github.go0dboy.articlenavigator.scheduler.core.SourceCollectionResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CollectionHardeningIntegrationTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var collection: RoomCollectionRepository
    private lateinit var ingestion: RoomIngestionRepository
    private lateinit var knowledge: RoomKnowledgeRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        collection = RoomCollectionRepository(database.collectionDao())
        ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        knowledge = RoomKnowledgeRepository(database.documentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun concurrentCollectionRunsClaimOnceReportAlreadyClaimedAndFetchOnce() = runTest {
        val source = source("concurrent")
        sources.upsert(source)
        val barrierSources = BarrierSourceRepository(sources, parties = 2)
        val adapter = BlockingCountingAdapter()
        val registry = SourceAdapterRegistry(listOf(adapter))
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val first = CollectionOrchestrator(
            barrierSources,
            collection,
            registry,
            clock = clock,
            runTokenFactory = { "run-a" },
        )
        val second = CollectionOrchestrator(
            barrierSources,
            collection,
            registry,
            clock = clock,
            runTokenFactory = { "run-b" },
        )

        val firstRun = async { first.run(CollectionRunContext(true)) }
        val secondRun = async { second.run(CollectionRunContext(true)) }
        adapter.started.await()
        while (!firstRun.isCompleted && !secondRun.isCompleted) yield()
        adapter.release.complete(Unit)
        val reports = awaitAll(firstRun, secondRun)
        val results = reports.flatMap { it.results }

        assertEquals(1, adapter.discoverCalls.get())
        assertEquals(1, results.count { it is SourceCollectionResult.Success })
        assertEquals(
            1,
            results.count {
                it is SourceCollectionResult.Skipped && it.reason == SkipReason.ALREADY_CLAIMED
            },
        )
    }

    @Test
    fun expiredLeaseCanBeClaimedByNextRunWithoutRelease() = runTest {
        val source = source("expired")
        sources.upsert(source)
        val first = collection.tryClaim(source.id, "run-a", now, now.plusSeconds(10))
        assertNotNull(first)

        val replacement = collection.tryClaim(
            source.id,
            "run-b",
            now.plusSeconds(11),
            now.plusSeconds(60),
        )

        assertNotNull(replacement)
        assertEquals("run-b", replacement!!.runToken)
    }

    @Test
    fun staleRunCannotOverwriteNewerCommittedState() = runTest {
        val source = source("stale")
        sources.upsert(source)
        val runA = collection.tryClaim(source.id, "run-a", now, now.plusSeconds(10))!!
        val runB = collection.tryClaim(source.id, "run-b", now.plusSeconds(11), now.plusSeconds(120))!!
        val itemB = discovered(source, "item-b", now.plusSeconds(11), "B")
        val committed = collection.commitSuccess(
            lease = runB,
            items = listOf(itemB),
            cursor = SourceCursor(source.id, etag = "etag-b", lastCheckedAt = now.plusSeconds(12)),
            completedAt = now.plusSeconds(12),
            nextCheckAt = now.plusSeconds(300),
            discoveredCount = 1,
        )
        assertEquals(CollectionCommitOutcome.APPLIED, committed)

        val stale = collection.commitSuccess(
            lease = runA,
            items = listOf(discovered(source, "item-a", now, "A")),
            cursor = SourceCursor(source.id, etag = "etag-a", lastCheckedAt = now.plusSeconds(13)),
            completedAt = now.plusSeconds(13),
            nextCheckAt = now.plusSeconds(30),
            discoveredCount = 1,
        )

        assertEquals(CollectionCommitOutcome.STALE, stale)
        assertEquals("etag-b", sources.loadCursor(source.id)?.etag)
        assertEquals(now.plusSeconds(300), sources.findById(source.id)?.nextCheckAt)
        assertNotNull(ingestion.findDiscoveredById(itemB.id))
        assertNull(ingestion.findDiscoveredById(DiscoveredItemId("item-a")))
        val state = database.collectionStateDao().findBySourceId(source.id.value)
        assertEquals(1, state?.lastDiscoveredCount)
        assertEquals(now.plusSeconds(12).toEpochMilli(), state?.lastAttemptAtEpochMillis)
    }

    @Test
    fun expiredLeaseCannotCommitEvenIfNoReplacementClaimedIt() = runTest {
        val source = source("expired-commit")
        sources.upsert(source)
        val lease = collection.tryClaim(source.id, "run-a", now, now.plusSeconds(10))!!

        val outcome = collection.commitFailure(
            lease = lease,
            completedAt = now.plusSeconds(11),
            nextCheckAt = now.plusSeconds(30),
            errorType = "late",
            errorMessage = "late",
        )

        assertEquals(CollectionCommitOutcome.STALE, outcome)
        assertEquals(now, sources.findById(source.id)?.nextCheckAt)
        assertNull(database.collectionStateDao().findBySourceId(source.id.value))
    }

    @Test
    fun commitSuccessRollsBackAllRowsWhenMiddleWriteFails() = runTest {
        val source = source("rollback")
        sources.upsert(source)
        val lease = collection.tryClaim(source.id, "run", now, now.plusSeconds(120))!!
        val valid = discovered(source, "valid", now, "valid")
        val invalid = valid.copy(
            id = DiscoveredItemId("invalid"),
            sourceId = SourceId("missing-source"),
            url = "https://example.test/invalid",
        )

        val result = runCatching {
            collection.commitSuccess(
                lease = lease,
                items = listOf(valid, invalid),
                cursor = SourceCursor(source.id, etag = "new-cursor"),
                completedAt = now.plusSeconds(1),
                nextCheckAt = now.plusSeconds(3600),
                discoveredCount = 2,
            )
        }

        assertTrue(result.isFailure)
        assertNull(ingestion.findDiscoveredById(valid.id))
        assertNull(sources.loadCursor(source.id))
        assertNull(database.collectionStateDao().findBySourceId(source.id.value))
        assertEquals(now, sources.findById(source.id)?.nextCheckAt)
        assertNull(collection.tryClaim(source.id, "other", now.plusSeconds(2), now.plusSeconds(200)))
    }

    @Test
    fun userSourceEditsInvalidateInFlightCollectorWithoutBeingReverted() = runTest {
        val source = source("settings")
        sources.upsert(source)
        val lease = collection.tryClaim(source.id, "run", now, now.plusSeconds(120))!!

        sources.upsert(
            source.copy(
                name = "User edited name",
                url = "https://user.example.test/new-feed.xml",
                enabled = false,
                pollPolicy = PollPolicy(Duration.ofHours(6), requiresUnmeteredNetwork = true),
                adapterType = "user-adapter",
                configurationJson = "{\"mode\":\"user\"}",
            ),
        )

        val outcome = collection.commitSuccess(
            lease = lease,
            items = listOf(discovered(source, "stale-item", now, "stale")),
            cursor = SourceCursor(source.id, etag = "stale"),
            completedAt = now.plusSeconds(1),
            nextCheckAt = now.plusSeconds(3600),
            discoveredCount = 1,
        )

        assertEquals(CollectionCommitOutcome.STALE, outcome)
        val stored = sources.findById(source.id)!!
        assertEquals("User edited name", stored.name)
        assertEquals("https://user.example.test/new-feed.xml", stored.url)
        assertFalse(stored.enabled)
        assertEquals(Duration.ofHours(6), stored.pollPolicy.interval)
        assertTrue(stored.pollPolicy.requiresUnmeteredNetwork)
        assertEquals("user-adapter", stored.adapterType)
        assertEquals("{\"mode\":\"user\"}", stored.configurationJson)
        assertEquals(1L, stored.settingsRevision)
        assertNull(sources.loadCursor(source.id))
        assertNull(ingestion.findDiscoveredById(DiscoveredItemId("stale-item")))
    }

    @Test
    fun repeatedSightingPreservesNewerIngestionStateAndFirstDiscoveryTime() = runTest {
        val source = source("sighting")
        sources.upsert(source)
        val firstSeen = now.minusSeconds(600)
        val id = DiscoveredItemId("stable")
        ingestion.upsertDiscovered(
            DiscoveredItem(
                id = id,
                sourceId = source.id,
                url = "https://example.test/article",
                canonicalUrl = "https://example.test/article",
                title = "Old title",
                discoveredAt = firstSeen,
                lastSeenAt = firstSeen,
                processingAttempts = 2,
            ),
        )
        val lease = collection.tryClaim(source.id, "run", now, now.plusSeconds(120))!!
        assertTrue(
            ingestion.markFetched(
                id,
                canonicalUrl = "https://example.test/article",
                resolvedUrl = "https://cdn.example.test/article",
                contentHash = "new-content-hash",
            ),
        )
        assertTrue(ingestion.markProcessed(id, "https://example.test/article"))

        val outcome = collection.commitSuccess(
            lease = lease,
            items = listOf(
                DiscoveredItem(
                    id = id,
                    sourceId = source.id,
                    url = "https://example.test/article",
                    canonicalUrl = "https://example.test/article",
                    title = "Fresh feed title",
                    publishedAt = now.minusSeconds(300),
                    discoveredAt = now,
                    lastSeenAt = now.plusSeconds(30),
                    status = DiscoveryStatus.DISCOVERED,
                ),
            ),
            cursor = SourceCursor(source.id, lastCheckedAt = now.plusSeconds(1)),
            completedAt = now.plusSeconds(1),
            nextCheckAt = now.plusSeconds(3600),
            discoveredCount = 1,
        )

        assertEquals(CollectionCommitOutcome.APPLIED, outcome)
        val stored = ingestion.findDiscoveredById(id)!!
        assertEquals(DiscoveryStatus.PROCESSED, stored.status)
        assertEquals("new-content-hash", stored.contentHash)
        assertEquals(2, stored.processingAttempts)
        assertEquals(firstSeen, stored.discoveredAt)
        assertEquals(now.plusSeconds(30), stored.lastSeenAt)
        assertEquals("Fresh feed title", stored.title)
        assertEquals("https://cdn.example.test/article", stored.resolvedUrl)
    }

    @Test
    fun archivedSourceDoesNotChangeSavedProvenanceSnapshot() = runTest {
        val source = source("provenance")
        sources.upsert(source)
        val document = Document(
            id = DocumentId("doc"),
            canonicalUrl = "https://example.test/article",
            title = "Article",
            normalizedText = "Body",
            contentHash = "hash",
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val provenance = DocumentProvenance(
            documentId = document.id,
            sourceId = source.id,
            discoveredUrl = document.canonicalUrl,
            resolvedUrl = document.canonicalUrl,
            discoveredAt = now.minusSeconds(10),
            fetchedAt = now,
            sourceNameSnapshot = source.name,
            sourceUrlSnapshot = source.url,
            sourceTypeSnapshot = source.type.name,
        )
        knowledge.persist(
            document,
            DocumentVersion(document.id, 1, "hash", "Body", now, "parser-v1"),
            provenance,
            SeenFingerprint("url-hash", "hash", source.id, now, ContentDisposition.SAVED),
        )

        sources.upsert(source.copy(name = "Renamed/archived", enabled = false))

        assertEquals(document, knowledge.findById(document.id))
        assertEquals(listOf(provenance), knowledge.provenance(document.id))
    }

    private fun source(id: String) = Source(
        id = SourceId(id),
        name = "Source $id",
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        configurationJson = "{\"mode\":\"original\"}",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    private fun discovered(source: Source, id: String, discoveredAt: Instant, title: String) = DiscoveredItem(
        id = DiscoveredItemId(id),
        sourceId = source.id,
        url = "https://example.test/$id",
        canonicalUrl = "https://example.test/$id",
        title = title,
        discoveredAt = discoveredAt,
        lastSeenAt = discoveredAt,
    )

    private class BlockingCountingAdapter : SourceAdapter {
        override val adapterType: String = "test"
        override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS)
        val discoverCalls = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult {
            discoverCalls.incrementAndGet()
            started.complete(Unit)
            release.await()
            return DiscoveryResult(emptyList(), SourceCursor(source.id, lastCheckedAt = Instant.EPOCH))
        }

        override suspend fun fetch(item: DiscoveredItem): FetchResult = error("not used")
    }

    private class BarrierSourceRepository(
        private val delegate: SourceRepository,
        private val parties: Int,
    ) : SourceRepository by delegate {
        private val arrivals = AtomicInteger(0)
        private val release = CompletableDeferred<Unit>()

        override suspend fun findDue(now: Instant): List<Source> = barrier(delegate.findDue(now))

        override suspend fun findDue(now: Instant, limit: Int): List<Source> = barrier(delegate.findDue(now, limit))

        private suspend fun barrier(result: List<Source>): List<Source> {
            if (arrivals.incrementAndGet() == parties) release.complete(Unit)
            release.await()
            return result
        }
    }
}
