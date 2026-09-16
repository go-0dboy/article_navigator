package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.pipeline.DefaultContentExtractor
import io.github.go0dboy.articlenavigator.pipeline.InboxService
import io.github.go0dboy.articlenavigator.pipeline.IngestionPipeline
import io.github.go0dboy.articlenavigator.pipeline.SourceAdapterResolver
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReliabilityStage2RegressionTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var ingestion: RoomIngestionRepository
    private lateinit var inbox: RoomInboxRepository
    private lateinit var knowledge: RoomKnowledgeRepository

    private val now = Instant.parse("2026-09-16T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sourceA = source("source-a", "Feed A")
    private val sourceB = source("source-b", "Feed B")
    private val articleBody = "<html><body><article><p>Shared durable body</p></article></body></html>".toByteArray()
    private val normalizedBody = DefaultContentExtractor()
        .extract(articleBody, "text/html; charset=utf-8", "https://example.test/shared")
        .normalizedText

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
        knowledge = RoomKnowledgeRepository(database.documentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun twoHandlersCannotFetchTheSameDiscovery() = runBlocking {
        sources.upsert(sourceA)
        val discovery = discovery("race-fetch", sourceA, "https://example.test/race")
        ingestion.upsertDiscovered(discovery)

        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val slow = adapter { item ->
            fetches.incrementAndGet()
            firstStarted.complete(Unit)
            withTimeout(TIMEOUT_MS) { releaseFirst.await() }
            success(item)
        }
        val fast = adapter { item ->
            fetches.incrementAndGet()
            success(item)
        }

        val firstRun = async { pipeline(slow).processReady(limit = 1) }
        withTimeout(TIMEOUT_MS) { firstStarted.await() }
        val secondReport = pipeline(fast).processReady(limit = 1)
        releaseFirst.complete(Unit)
        withTimeout(TIMEOUT_MS) { firstRun.await() }

        assertEquals(1, fetches.get())
        assertEquals(0, secondReport.processed)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertNull(database.articleProcessingDao().item(discovery.id.value)?.processingLeaseToken)
        assertNull(ingestion.loadRawContent(discovery.id))
    }

    @Test
    fun expiredOwnerCannotPersistRawFailureSuccessOrReleaseReplacementLease() = runBlocking {
        sources.upsert(sourceA)
        val discovery = discovery("lease-replacement", sourceA, "https://example.test/lease")
        ingestion.upsertDiscovered(discovery)

        val old = checkNotNull(
            ingestion.tryClaimNext("old-owner", now, now.plusSeconds(1), isUnmeteredNetwork = true),
        )
        val replacementAt = now.plusSeconds(2)
        val newer = checkNotNull(
            ingestion.tryClaimNext("new-owner", replacementAt, replacementAt.plusSeconds(60), isUnmeteredNetwork = true),
        )
        val staleRaw = RawContent(
            discoveredItemId = discovery.id,
            contentType = "text/html",
            payload = "stale".toByteArray(),
            resolvedUrl = discovery.url,
            fetchedAt = replacementAt,
            httpStatus = 200,
            expiresAt = replacementAt.plusSeconds(60),
        )

        assertFalse(ingestion.storeRawContent(old, staleRaw, replacementAt))
        assertFalse(ingestion.markFetched(old, discovery.url, discovery.url, "stale-hash", replacementAt))
        assertFalse(
            ingestion.markFailed(
                old,
                processingAttempts = 1,
                nextProcessingAt = replacementAt.plusSeconds(30),
                lastProcessingError = "late failure",
                at = replacementAt,
            ),
        )
        assertFalse(ingestion.markSkipped(old, discovery.url, "late skip", replacementAt))
        ingestion.releaseProcessing(old)

        assertEquals("new-owner", database.articleProcessingDao().item(discovery.id.value)?.processingLeaseToken)
        assertNull(ingestion.loadRawContent(discovery.id))

        val item = InboxItem(
            id = InboxItemId("lease-inbox"),
            canonicalUrl = discovery.url,
            title = "Lease winner",
            normalizedText = normalizedBody,
            contentHash = sha256(normalizedBody),
            createdAt = replacementAt,
            updatedAt = replacementAt,
        )
        val origin = origin(item.id, discovery, sourceA, replacementAt)
        assertEquals(
            IngestionFinalizeOutcome.ADDED_TO_INBOX,
            ingestion.finalizeSuccess(newer, item, origin, sha256(discovery.url), replacementAt.plusSeconds(1)),
        )

        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertNull(database.articleProcessingDao().item(discovery.id.value)?.processingLeaseToken)
        assertNotNull(inbox.findById(item.id))
    }

    @Test
    fun infrastructureFailureAfterClaimReleasesLeaseForImmediateRetry() = runBlocking {
        sources.upsert(sourceA)
        val discovery = discovery("infra-release", sourceA, "https://example.test/infra")
        ingestion.upsertDiscovered(discovery)
        val brokenSources = object : SourceRepository by sources {
            override suspend fun findById(id: SourceId): Source? {
                throw IllegalStateException("shared storage unavailable after claim")
            }
        }

        val failure = runCatching {
            pipeline(adapter(::success), brokenSources).processReady(limit = 1)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
        assertNull(database.articleProcessingDao().item(discovery.id.value)?.processingLeaseToken)
        val retryLease = ingestion.tryClaimNext(
            "retry-owner",
            now,
            now.plusSeconds(60),
            isUnmeteredNetwork = true,
        )
        assertNotNull(retryLease)
        ingestion.releaseProcessing(checkNotNull(retryLease))
    }

    @Test
    fun saveCommittedBeforeLateOriginRoutesThatOriginIntoSavedDocument() = runBlocking {
        val seeded = seedSharedInbox()
        val discoveryB = discovery("origin-b", sourceB, seeded.item.canonicalUrl)
        ingestion.upsertDiscovered(discoveryB)
        val fetchStarted = CompletableDeferred<Unit>()
        val allowFetch = CompletableDeferred<Unit>()
        val processing = async {
            pipeline(adapter { item ->
                fetchStarted.complete(Unit)
                withTimeout(TIMEOUT_MS) { allowFetch.await() }
                success(item)
            }).processReady(limit = 1)
        }
        withTimeout(TIMEOUT_MS) { fetchStarted.await() }

        val documentId = InboxService(inbox, clock).save(seeded.item.id)
        allowFetch.complete(Unit)
        withTimeout(TIMEOUT_MS) { processing.await() }

        assertNull(inbox.findById(seeded.item.id))
        val provenances = knowledge.provenance(documentId)
        assertEquals(2, provenances.size)
        assertEquals(setOf(sourceA.id, sourceB.id), provenances.map { it.sourceId }.toSet())
        assertEquals(ContentDisposition.SAVED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceA.id)?.disposition)
        assertEquals(ContentDisposition.SAVED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceB.id)?.disposition)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discoveryB.id)?.status)
        assertNull(ingestion.loadRawContent(discoveryB.id))
        assertNull(database.articleProcessingDao().item(discoveryB.id.value)?.processingLeaseToken)
    }

    @Test
    fun rejectCommittedWhileOldProcessingRunsCannotRecreateInbox() = runBlocking {
        val seeded = seedSharedInbox()
        val discoveryB = discovery("reject-origin-b", sourceB, seeded.item.canonicalUrl)
        ingestion.upsertDiscovered(discoveryB)
        val fetchStarted = CompletableDeferred<Unit>()
        val allowFetch = CompletableDeferred<Unit>()
        val processing = async {
            pipeline(adapter { item ->
                fetchStarted.complete(Unit)
                withTimeout(TIMEOUT_MS) { allowFetch.await() }
                success(item)
            }).processReady(limit = 1)
        }
        withTimeout(TIMEOUT_MS) { fetchStarted.await() }

        assertTrue(InboxService(inbox, clock).reject(seeded.item.id))
        allowFetch.complete(Unit)
        val report = withTimeout(TIMEOUT_MS) { processing.await() }

        assertEquals(1, report.alreadyKnown)
        assertNull(inbox.findById(seeded.item.id))
        assertEquals(ContentDisposition.REJECTED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceA.id)?.disposition)
        assertEquals(ContentDisposition.REJECTED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceB.id)?.disposition)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discoveryB.id)?.status)
        assertNull(ingestion.loadRawContent(discoveryB.id))
        assertNull(database.articleProcessingDao().item(discoveryB.id.value)?.processingLeaseToken)
    }

    @Test
    fun saveAndRejectRaceHasExactlyOneCommittedWinnerAndRepeatSaveFails() = runBlocking {
        val seeded = seedSharedInbox()
        val service = InboxService(inbox, clock)
        val start = CompletableDeferred<Unit>()

        val save = async {
            withTimeout(TIMEOUT_MS) { start.await() }
            try {
                SaveResult(service.save(seeded.item.id), null)
            } catch (error: NoSuchElementException) {
                SaveResult(null, error)
            }
        }
        val reject = async {
            withTimeout(TIMEOUT_MS) { start.await() }
            service.reject(seeded.item.id)
        }
        start.complete(Unit)
        val saveResult = withTimeout(TIMEOUT_MS) { save.await() }
        val rejectResult = withTimeout(TIMEOUT_MS) { reject.await() }

        assertTrue((saveResult.documentId != null) xor rejectResult)
        assertNull(inbox.findById(seeded.item.id))
        if (saveResult.documentId != null) {
            assertNotNull(knowledge.findById(saveResult.documentId))
            assertEquals(ContentDisposition.SAVED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceA.id)?.disposition)
            try {
                service.save(seeded.item.id)
                throw AssertionError("Repeat Save must not succeed")
            } catch (_: NoSuchElementException) {
                // expected
            }
        } else {
            assertNotNull(saveResult.error)
            assertNull(knowledge.findByCanonicalUrl(seeded.item.canonicalUrl))
            assertEquals(ContentDisposition.REJECTED, knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceA.id)?.disposition)
        }
    }

    @Test
    fun sameMaterialFromTwoSourcesProducesOneInboxWithBothOrigins() = runBlocking {
        sources.upsert(sourceA)
        sources.upsert(sourceB)
        val a = discovery("same-a", sourceA, "https://example.test/a-copy")
        val b = discovery("same-b", sourceB, "https://example.test/b-copy")
        ingestion.upsertDiscovered(a)
        ingestion.upsertDiscovered(b)

        val reports = listOf(
            async { pipeline(adapter(::success)).processReady(limit = 1) },
            async { pipeline(adapter(::success)).processReady(limit = 1) },
        ).awaitAll()

        assertEquals(2, reports.sumOf { it.processed })
        val pending = inbox.listPending()
        assertEquals(1, pending.size)
        val origins = inbox.origins(pending.single().id)
        assertEquals(2, origins.size)
        assertEquals(setOf(sourceA.id, sourceB.id), origins.map { it.sourceId }.toSet())
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(a.id)?.status)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(b.id)?.status)
        assertNull(ingestion.loadRawContent(a.id))
        assertNull(ingestion.loadRawContent(b.id))
    }

    private suspend fun seedSharedInbox(): SeededInbox {
        sources.upsert(sourceA)
        sources.upsert(sourceB)
        val item = InboxItem(
            id = InboxItemId("shared-inbox"),
            canonicalUrl = "https://example.test/shared",
            title = "Shared",
            normalizedText = normalizedBody,
            contentHash = sha256(normalizedBody),
            createdAt = now.minusSeconds(30),
            updatedAt = now.minusSeconds(30),
        )
        val discoveredA = discovery("origin-a", sourceA, item.canonicalUrl)
        ingestion.upsertDiscovered(discoveredA)
        val originA = origin(item.id, discoveredA, sourceA, now.minusSeconds(10))
        inbox.put(item, originA)
        return SeededInbox(item, originA)
    }

    private fun pipeline(
        adapter: SourceAdapter,
        sourceRepository: SourceRepository = sources,
    ) = IngestionPipeline(
        sourceRepository = sourceRepository,
        ingestionRepository = ingestion,
        adapterResolver = SourceAdapterResolver { adapter },
        clock = clock,
    )

    private fun adapter(fetch: suspend (DiscoveredItem) -> FetchResult) = object : SourceAdapter {
        override val adapterType: String = "test"
        override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS)
        override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = error("not used")
        override suspend fun fetch(item: DiscoveredItem): FetchResult = fetch(item)
    }

    private fun success(item: DiscoveredItem) = FetchResult(
        item = item,
        statusCode = 200,
        contentType = "text/html; charset=utf-8",
        body = articleBody,
        resolvedUrl = item.url,
    )

    private fun source(id: String, name: String) = Source(
        id = SourceId(id),
        name = name,
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        createdAt = now.minusSeconds(3_600),
    )

    private fun discovery(id: String, source: Source, url: String) = DiscoveredItem(
        id = DiscoveredItemId(id),
        sourceId = source.id,
        url = url,
        canonicalUrl = url,
        title = "Article $id",
        discoveredAt = now.minusSeconds(60),
    )

    private fun origin(
        inboxId: InboxItemId,
        item: DiscoveredItem,
        source: Source,
        fetchedAt: Instant,
    ) = InboxOrigin(
        inboxItemId = inboxId,
        discoveredItemId = item.id,
        sourceId = source.id,
        discoveredUrl = item.url,
        resolvedUrl = item.url,
        canonicalUrl = item.canonicalUrl ?: item.url,
        discoveredAt = item.discoveredAt,
        fetchedAt = fetchedAt,
        sourceNameSnapshot = source.name,
        sourceUrlSnapshot = source.url,
        sourceTypeSnapshot = source.type.name,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class SeededInbox(
        val item: InboxItem,
        val originA: InboxOrigin,
    )

    private data class SaveResult(
        val documentId: io.github.go0dboy.articlenavigator.core.model.DocumentId?,
        val error: Throwable?,
    )

    companion object {
        private const val TIMEOUT_MS = 5_000L
    }
}
