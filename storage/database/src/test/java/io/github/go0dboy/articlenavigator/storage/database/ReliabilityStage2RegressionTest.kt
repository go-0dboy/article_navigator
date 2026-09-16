package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        ingestion = RoomIngestionRepository(database.ingestionDao())
        inbox = RoomInboxRepository(database.inboxDao())
        knowledge = RoomKnowledgeRepository(database.documentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun concurrentPipelinesMustNotFetchTheSameDiscoveryTwice() = runTest {
        sources.upsert(sourceA)
        val discovery = discovery("race-fetch", sourceA, "https://example.test/race")
        ingestion.upsertDiscovered(discovery)

        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val fetches = AtomicInteger(0)
        val slow = adapter { item ->
            fetches.incrementAndGet()
            firstStarted.complete(Unit)
            releaseFirst.await()
            success(item)
        }
        val fast = adapter { item ->
            fetches.incrementAndGet()
            success(item)
        }

        val firstRun = async { pipeline(slow).processReady(limit = 1) }
        firstStarted.await()
        pipeline(fast).processReady(limit = 1)
        releaseFirst.complete(Unit)
        firstRun.await()

        assertEquals("Only one processing owner may perform the article request", 1, fetches.get())
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
    }

    @Test
    fun lateFailureMustNotOverwriteAlreadyProcessedDiscovery() = runTest {
        sources.upsert(sourceA)
        val discovery = discovery("late-failure", sourceA, "https://example.test/late")
        ingestion.upsertDiscovered(discovery)

        assertEquals(true, ingestion.markProcessed(discovery.id, discovery.canonicalUrl))
        assertEquals(
            false,
            ingestion.markFailed(
                id = discovery.id,
                processingAttempts = 1,
                nextProcessingAt = now.plusSeconds(60),
                lastProcessingError = "late stale failure",
            ),
        )
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
    }

    @Test
    fun saveMustIncludeOriginAttachedAfterServiceRead() = runTest {
        val seeded = seedInbox()
        val captured = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val blocking = BlockingOriginsRepository(inbox, captured, resume)
        val service = InboxService(blocking, clock)

        val save = async { service.save(seeded.item.id) }
        captured.await()
        inbox.attachOrigin(seeded.item.id, seeded.originB)
        resume.complete(Unit)
        val documentId = save.await()

        val provenances = knowledge.provenance(documentId)
        assertEquals("Save must atomically consume every origin present at commit time", 2, provenances.size)
        assertEquals(setOf(sourceA.id, sourceB.id), provenances.map { it.sourceId }.toSet())
        assertEquals(null, inbox.findById(seeded.item.id))
    }

    @Test
    fun rejectMustFingerprintOriginAttachedAfterServiceRead() = runTest {
        val seeded = seedInbox()
        val captured = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val blocking = BlockingOriginsRepository(inbox, captured, resume)
        val service = InboxService(blocking, clock)

        val reject = async { service.reject(seeded.item.id) }
        captured.await()
        inbox.attachOrigin(seeded.item.id, seeded.originB)
        resume.complete(Unit)
        reject.await()

        val fingerprint = knowledge.findSeen(sha256(seeded.item.canonicalUrl), sourceB.id)
        assertNotNull("Reject must not lose a concurrently attached origin", fingerprint)
        assertEquals(ContentDisposition.REJECTED, fingerprint?.disposition)
        assertEquals(null, inbox.findById(seeded.item.id))
    }

    private suspend fun seedInbox(): SeededInbox {
        sources.upsert(sourceA)
        sources.upsert(sourceB)
        val item = InboxItem(
            id = InboxItemId("shared-inbox"),
            canonicalUrl = "https://example.test/shared",
            title = "Shared",
            normalizedText = "Shared durable body",
            contentHash = sha256("Shared durable body"),
            createdAt = now.minusSeconds(30),
            updatedAt = now.minusSeconds(30),
        )
        val discoveredA = discovery("origin-a", sourceA, item.canonicalUrl)
        val discoveredB = discovery("origin-b", sourceB, item.canonicalUrl)
        ingestion.upsertDiscovered(discoveredA)
        ingestion.upsertDiscovered(discoveredB)
        val originA = origin(item.id, discoveredA, sourceA)
        val originB = origin(item.id, discoveredB, sourceB)
        inbox.put(item, originA)
        return SeededInbox(item, originA, originB)
    }

    private fun pipeline(adapter: SourceAdapter) = IngestionPipeline(
        sourceRepository = sources,
        ingestionRepository = ingestion,
        inboxRepository = inbox,
        knowledgeRepository = knowledge,
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
        body = "<html><body><article><p>Shared durable body</p></article></body></html>".toByteArray(),
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

    private fun origin(inboxId: InboxItemId, item: DiscoveredItem, source: Source) = InboxOrigin(
        inboxItemId = inboxId,
        discoveredItemId = item.id,
        sourceId = source.id,
        discoveredUrl = item.url,
        resolvedUrl = item.url,
        canonicalUrl = item.canonicalUrl ?: item.url,
        discoveredAt = item.discoveredAt,
        fetchedAt = now.minusSeconds(10),
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
        val originB: InboxOrigin,
    )
}

private class BlockingOriginsRepository(
    private val delegate: InboxRepository,
    private val captured: CompletableDeferred<Unit>,
    private val resume: CompletableDeferred<Unit>,
) : InboxRepository by delegate {
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> {
        val snapshot = delegate.origins(id)
        captured.complete(Unit)
        resume.await()
        return snapshot
    }
}
