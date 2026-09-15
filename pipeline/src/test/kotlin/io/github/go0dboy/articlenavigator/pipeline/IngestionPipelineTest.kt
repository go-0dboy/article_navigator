package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.KnowledgeRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionPipelineTest {
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val source = Source(
        id = SourceId("source-1"),
        name = "Feed",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofMinutes(30)),
        adapterType = "test",
        createdAt = now,
    )

    @Test
    fun `new article is extracted and placed into Inbox`() = runTest {
        val discovered = discovered("https://example.test/article")
        val ingestion = FakeIngestionRepository(discovered)
        val inbox = FakeInboxRepository(ingestion)
        val knowledge = FakeKnowledgeRepository()
        val pipeline = pipeline(ingestion, inbox, knowledge) {
            FetchResult(
                it,
                200,
                "text/html; charset=utf-8",
                "<html><head><title>Article title</title></head><body><article><p>Useful body text.</p></article></body></html>".toByteArray(),
            )
        }

        val report = pipeline.processReady()

        assertEquals(1, report.addedToInbox)
        assertEquals(0, report.failed)
        assertEquals(0, report.skipped)
        assertEquals(1, inbox.items.size)
        assertEquals("Article title", inbox.items.values.single().title)
        assertTrue(inbox.items.values.single().normalizedText.contains("Useful body text."))
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
        assertEquals(null, ingestion.raw[discovered.id])
    }

    @Test
    fun `previously dismissed URL is not fetched again`() = runTest {
        val discovered = discovered("https://example.test/seen")
        val ingestion = FakeIngestionRepository(discovered)
        val inbox = FakeInboxRepository(ingestion)
        val knowledge = FakeKnowledgeRepository().apply {
            seenBySource[source.id] = SeenFingerprint(
                canonicalUrlHash = sha256ForTest("https://example.test/seen"),
                contentHash = null,
                sourceId = source.id,
                seenAt = now.minusSeconds(60),
                disposition = ContentDisposition.REJECTED,
            )
        }
        var fetches = 0
        val pipeline = pipeline(ingestion, inbox, knowledge) {
            fetches++
            error("fetch must not run")
        }

        val report = pipeline.processReady()

        assertEquals(1, report.alreadyKnown)
        assertEquals(0, fetches)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
    }

    @Test
    fun `network failure persists retry backoff`() = runTest {
        val discovered = discovered("https://example.test/failure")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion, FakeInboxRepository(ingestion), FakeKnowledgeRepository()) {
            throw IOException("network down")
        }

        val report = pipeline.processReady()
        val failed = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.failed)
        assertEquals(0, report.skipped)
        assertEquals(DiscoveryStatus.FAILED, failed.status)
        assertEquals(1, failed.processingAttempts)
        assertEquals(now.plus(Duration.ofMinutes(15)), failed.nextProcessingAt)
        assertTrue(failed.lastProcessingError!!.contains("network down"))
    }

    @Test
    fun `server failure remains retryable`() = runTest {
        val discovered = discovered("https://example.test/server-failure")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion, FakeInboxRepository(ingestion), FakeKnowledgeRepository()) {
            FetchResult(it, 503, "text/plain", "unavailable".toByteArray())
        }

        val report = pipeline.processReady()
        val failed = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.failed)
        assertEquals(DiscoveryStatus.FAILED, failed.status)
        assertEquals(now.plus(Duration.ofMinutes(15)), failed.nextProcessingAt)
    }

    @Test
    fun `permanent client response is skipped without retry`() = runTest {
        val discovered = discovered("https://example.test/missing")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion, FakeInboxRepository(ingestion), FakeKnowledgeRepository()) {
            FetchResult(it, 404, "text/plain", "missing".toByteArray())
        }

        val report = pipeline.processReady()
        val skipped = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.skipped)
        assertEquals(0, report.failed)
        assertEquals(DiscoveryStatus.SKIPPED, skipped.status)
        assertEquals(null, skipped.nextProcessingAt)
        assertTrue(skipped.lastProcessingError!!.contains("404"))
        assertTrue(ingestion.findReadyForProcessing(now.plus(Duration.ofDays(7)), 10).isEmpty())
    }

    @Test
    fun `unsupported content is skipped and temporary payload is removed`() = runTest {
        val discovered = discovered("https://example.test/image")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion, FakeInboxRepository(ingestion), FakeKnowledgeRepository()) {
            FetchResult(it, 200, "image/png", byteArrayOf(1, 2, 3))
        }

        val report = pipeline.processReady()
        val skipped = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.skipped)
        assertEquals(DiscoveryStatus.SKIPPED, skipped.status)
        assertEquals(null, ingestion.raw[discovered.id])
        assertTrue(skipped.lastProcessingError!!.contains("Unsupported content type"))
    }

    @Test
    fun `same normalized content merges provenance into existing Inbox item`() = runTest {
        val discovered = discovered("https://example.test/alternate")
        val ingestion = FakeIngestionRepository(discovered)
        val inbox = FakeInboxRepository(ingestion)
        val body = "<article><p>Same durable content</p></article>".toByteArray()
        val extracted = DefaultContentExtractor().extract(body, "text/html", discovered.url)
        val existing = InboxItem(
            id = InboxItemId("existing"),
            canonicalUrl = "https://example.test/original",
            title = "Original",
            normalizedText = extracted.normalizedText,
            contentHash = sha256ForTest(extracted.normalizedText),
            createdAt = now.minusSeconds(10),
            updatedAt = now.minusSeconds(10),
        )
        inbox.items[existing.id] = existing
        val pipeline = pipeline(ingestion, inbox, FakeKnowledgeRepository()) {
            FetchResult(it, 200, "text/html", body)
        }

        val report = pipeline.processReady()

        assertEquals(1, report.mergedIntoInbox)
        assertEquals(1, inbox.items.size)
        assertEquals(1, inbox.origins.getValue(existing.id).size)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
    }

    private fun pipeline(
        ingestion: FakeIngestionRepository,
        inbox: FakeInboxRepository,
        knowledge: FakeKnowledgeRepository,
        fetch: suspend (DiscoveredItem) -> FetchResult,
    ) = IngestionPipeline(
        sourceRepository = FakeSourceRepository(source),
        ingestionRepository = ingestion,
        inboxRepository = inbox,
        knowledgeRepository = knowledge,
        adapterResolver = SourceAdapterResolver {
            object : SourceAdapter {
                override val adapterType = "test"
                override val supportedTypes = setOf(SourceType.RSS)
                override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult =
                    error("not used")
                override suspend fun fetch(item: DiscoveredItem): FetchResult = fetch(item)
            }
        },
        clock = clock,
    )

    private fun discovered(url: String) = DiscoveredItem(
        id = DiscoveredItemId(url.substringAfterLast('/')),
        sourceId = source.id,
        url = url,
        canonicalUrl = url,
        title = "Feed title",
        discoveredAt = now.minusSeconds(30),
    )
}

private class FakeSourceRepository(private val source: Source) : SourceRepository {
    override suspend fun upsert(source: Source) = Unit
    override suspend fun findById(id: SourceId): Source? = source.takeIf { it.id == id }
    override suspend fun findDue(now: Instant): List<Source> = emptyList()
    override suspend fun listAll(): List<Source> = listOf(source)
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = null
    override suspend fun saveCursor(cursor: SourceCursor) = Unit
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean = false
}

private class FakeIngestionRepository(initial: DiscoveredItem) : IngestionRepository {
    val items = linkedMapOf(initial.id to initial)
    val raw = mutableMapOf<DiscoveredItemId, RawContent>()
    override suspend fun upsertDiscovered(item: DiscoveredItem) { items[item.id] = item }
    override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = items[id]
    override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? =
        items.values.firstOrNull { it.sourceId == sourceId && it.url == url }
    override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> = items.values
        .filter { item ->
            if (item.status == DiscoveryStatus.DISCOVERED) {
                true
            } else if (item.status == DiscoveryStatus.FAILED) {
                val retryAt = item.nextProcessingAt
                retryAt == null || !retryAt.isAfter(now)
            } else {
                false
            }
        }
        .take(limit)
    override suspend fun storeRawContent(content: RawContent) { raw[content.discoveredItemId] = content }
    override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = raw[id]
    override suspend fun deleteRawContent(id: DiscoveredItemId) { raw.remove(id) }

    suspend fun commitOrigin(origin: InboxOrigin) {
        items[origin.discoveredItemId]?.let { item ->
            items[origin.discoveredItemId] = item.copy(
                status = DiscoveryStatus.PROCESSED,
                nextProcessingAt = null,
                lastProcessingError = null,
            )
        }
        raw.remove(origin.discoveredItemId)
    }
}

private class FakeInboxRepository(
    private val ingestion: FakeIngestionRepository,
) : InboxRepository {
    val items = linkedMapOf<InboxItemId, InboxItem>()
    val origins = mutableMapOf<InboxItemId, MutableList<InboxOrigin>>()

    override suspend fun put(item: InboxItem, origin: InboxOrigin) {
        items[item.id] = item
        origins.getOrPut(item.id) { mutableListOf() }.add(origin)
        ingestion.commitOrigin(origin)
    }

    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) {
        origins.getOrPut(itemId) { mutableListOf() }.add(origin)
        ingestion.commitOrigin(origin)
    }

    override suspend fun listPending(limit: Int): List<InboxItem> = items.values.take(limit)
    override suspend fun findById(id: InboxItemId): InboxItem? = items[id]
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? =
        items.values.firstOrNull { it.canonicalUrl == canonicalUrl }
    override suspend fun findByContentHash(contentHash: String): InboxItem? =
        items.values.firstOrNull { it.contentHash == contentHash }
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> = origins[id].orEmpty()
    override suspend fun discard(id: InboxItemId, disposition: ContentDisposition, fingerprints: List<SeenFingerprint>) {
        items.remove(id)
        origins.remove(id)
    }
    override suspend fun save(
        id: InboxItemId,
        document: Document,
        version: DocumentVersion,
        provenances: List<DocumentProvenance>,
        fingerprints: List<SeenFingerprint>,
    ) {
        items.remove(id)
        origins.remove(id)
    }
}

private class FakeKnowledgeRepository : KnowledgeRepository {
    val documents = mutableListOf<Document>()
    val seenBySource = mutableMapOf<SourceId, SeenFingerprint>()
    override suspend fun persist(document: Document, version: DocumentVersion, provenance: DocumentProvenance, fingerprint: SeenFingerprint) { documents += document }
    override suspend fun findById(id: DocumentId): Document? = documents.firstOrNull { it.id == id }
    override suspend fun findByCanonicalUrl(canonicalUrl: String): Document? = documents.firstOrNull { it.canonicalUrl == canonicalUrl }
    override suspend fun findByContentHash(contentHash: String): Document? = documents.firstOrNull { it.contentHash == contentHash }
    override suspend fun versions(documentId: DocumentId): List<DocumentVersion> = emptyList()
    override suspend fun provenance(documentId: DocumentId): List<DocumentProvenance> = emptyList()
    override suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint? =
        seenBySource[sourceId]?.takeIf { it.canonicalUrlHash == canonicalUrlHash }
    override suspend fun recordDiscovery(documentId: DocumentId, provenance: DocumentProvenance, fingerprint: SeenFingerprint, discoveredItemId: DiscoveredItemId) {
        seenBySource[fingerprint.sourceId] = fingerprint
    }
    override suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant) = Unit
}

private fun sha256ForTest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
