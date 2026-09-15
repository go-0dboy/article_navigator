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
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngestionHttpRetrySemanticsTest {
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
    )

    @Test
    fun retryAfterSecondsOverridesLocalBackoffFor429And503() = runTest {
        listOf(429 to 120L, 503 to 45L).forEach { (status, seconds) ->
            val stored = runResponse(status, mapOf("Retry-After" to seconds.toString()))
            assertEquals(DiscoveryStatus.FAILED, stored.status)
            assertEquals(now.plusSeconds(seconds), stored.nextProcessingAt)
        }
    }

    @Test
    fun retryAfterHttpDateOverridesLocalBackoff() = runTest {
        val header = ZonedDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val stored = runResponse(503, mapOf("Retry-After" to header))

        assertEquals(now.plusSeconds(1800), stored.nextProcessingAt)
    }

    @Test
    fun pastOrInvalidRetryAfterFallsBackToLocalBackoff() = runTest {
        val past = ZonedDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        listOf(past, "not-a-date").forEach { value ->
            val stored = runResponse(503, mapOf("Retry-After" to value))
            assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
        }
    }

    @Test
    fun permanentClientErrorsAreSkippedWithoutRetry() = runTest {
        listOf(401, 403, 404).forEach { status ->
            val stored = runResponse(status)
            assertEquals(DiscoveryStatus.SKIPPED, stored.status)
            assertNull(stored.nextProcessingAt)
            assertEquals(0, stored.processingAttempts)
        }
    }

    @Test
    fun http500UsesTransientLocalBackoff() = runTest {
        val stored = runResponse(500)

        assertEquals(DiscoveryStatus.FAILED, stored.status)
        assertEquals(1, stored.processingAttempts)
        assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
    }

    @Test
    fun transportTimeoutUsesTransientLocalBackoff() = runTest {
        val repository = RetryIngestionRepository(discovered())
        val pipeline = pipeline(repository) { throw SocketTimeoutException("timed out") }

        pipeline.processReady()

        val stored = repository.item
        assertEquals(DiscoveryStatus.FAILED, stored.status)
        assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
    }

    private suspend fun runResponse(status: Int, headers: Map<String, String> = emptyMap()): DiscoveredItem {
        val repository = RetryIngestionRepository(discovered())
        val pipeline = pipeline(repository) { item ->
            FetchResult(
                item = item,
                statusCode = status,
                contentType = "text/plain",
                body = "response".toByteArray(),
                fetchedHeaders = headers,
            )
        }
        pipeline.processReady()
        return repository.item
    }

    private fun pipeline(
        repository: RetryIngestionRepository,
        fetch: suspend (DiscoveredItem) -> FetchResult,
    ) = IngestionPipeline(
        sourceRepository = SingleSourceRepository(source),
        ingestionRepository = repository,
        inboxRepository = EmptyInboxRepository,
        knowledgeRepository = EmptyKnowledgeRepository,
        adapterResolver = SourceAdapterResolver {
            object : SourceAdapter {
                override val adapterType = "test"
                override val supportedTypes = setOf(SourceType.RSS)
                override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = error("not used")
                override suspend fun fetch(item: DiscoveredItem): FetchResult = fetch(item)
            }
        },
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun discovered() = DiscoveredItem(
        id = DiscoveredItemId("item"),
        sourceId = source.id,
        url = "https://example.test/article",
        canonicalUrl = "https://example.test/article",
        discoveredAt = now.minusSeconds(30),
    )
}

private class RetryIngestionRepository(initial: DiscoveredItem) : IngestionRepository {
    var item: DiscoveredItem = initial
    private var raw: RawContent? = null

    override suspend fun upsertDiscovered(item: DiscoveredItem) { this.item = item }
    override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = item.takeIf { it.id == id }
    override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? =
        item.takeIf { it.sourceId == sourceId && it.url == url }
    override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> =
        if (item.status == DiscoveryStatus.DISCOVERED ||
            (item.status == DiscoveryStatus.FAILED && (item.nextProcessingAt == null || !item.nextProcessingAt!!.isAfter(now)))
        ) listOf(item).take(limit) else emptyList()

    override suspend fun markProcessed(id: DiscoveredItemId, canonicalUrl: String?): Boolean {
        if (item.id != id) return false
        item = item.copy(canonicalUrl = canonicalUrl ?: item.canonicalUrl, status = DiscoveryStatus.PROCESSED, nextProcessingAt = null, lastProcessingError = null)
        return true
    }

    override suspend fun markFetched(id: DiscoveredItemId, canonicalUrl: String, resolvedUrl: String?, contentHash: String): Boolean {
        if (item.id != id) return false
        item = item.copy(canonicalUrl = canonicalUrl, resolvedUrl = resolvedUrl ?: item.resolvedUrl, contentHash = contentHash, status = DiscoveryStatus.FETCHED, nextProcessingAt = null, lastProcessingError = null)
        return true
    }

    override suspend fun markFailed(id: DiscoveredItemId, processingAttempts: Int, nextProcessingAt: Instant, lastProcessingError: String): Boolean {
        if (item.id != id) return false
        item = item.copy(status = DiscoveryStatus.FAILED, processingAttempts = processingAttempts, nextProcessingAt = nextProcessingAt, lastProcessingError = lastProcessingError)
        return true
    }

    override suspend fun markSkipped(id: DiscoveredItemId, canonicalUrl: String?, lastProcessingError: String): Boolean {
        if (item.id != id) return false
        item = item.copy(canonicalUrl = canonicalUrl ?: item.canonicalUrl, status = DiscoveryStatus.SKIPPED, nextProcessingAt = null, lastProcessingError = lastProcessingError)
        return true
    }

    override suspend fun storeRawContent(content: RawContent) { raw = content }
    override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = raw?.takeIf { it.discoveredItemId == id }
    override suspend fun deleteRawContent(id: DiscoveredItemId) { if (raw?.discoveredItemId == id) raw = null }
}

private class SingleSourceRepository(private val source: Source) : SourceRepository {
    override suspend fun upsert(source: Source) = Unit
    override suspend fun findById(id: SourceId): Source? = source.takeIf { it.id == id }
    override suspend fun findDue(now: Instant): List<Source> = emptyList()
    override suspend fun listAll(): List<Source> = listOf(source)
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = null
    override suspend fun saveCursor(cursor: SourceCursor) = Unit
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean = false
}

private object EmptyInboxRepository : InboxRepository {
    override suspend fun put(item: InboxItem, origin: InboxOrigin) = Unit
    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) = Unit
    override suspend fun listPending(limit: Int): List<InboxItem> = emptyList()
    override suspend fun findById(id: InboxItemId): InboxItem? = null
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? = null
    override suspend fun findByContentHash(contentHash: String): InboxItem? = null
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> = emptyList()
    override suspend fun discard(id: InboxItemId, disposition: ContentDisposition, fingerprints: List<SeenFingerprint>) = Unit
    override suspend fun save(id: InboxItemId, document: Document, version: DocumentVersion, provenances: List<DocumentProvenance>, fingerprints: List<SeenFingerprint>) = Unit
}

private object EmptyKnowledgeRepository : KnowledgeRepository {
    override suspend fun persist(document: Document, version: DocumentVersion, provenance: DocumentProvenance, fingerprint: SeenFingerprint) = Unit
    override suspend fun findById(id: DocumentId): Document? = null
    override suspend fun findByCanonicalUrl(canonicalUrl: String): Document? = null
    override suspend fun findByContentHash(contentHash: String): Document? = null
    override suspend fun versions(documentId: DocumentId): List<DocumentVersion> = emptyList()
    override suspend fun provenance(documentId: DocumentId): List<DocumentProvenance> = emptyList()
    override suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint? = null
    override suspend fun recordDiscovery(documentId: DocumentId, provenance: DocumentProvenance, fingerprint: SeenFingerprint, discoveredItemId: DiscoveredItemId) = Unit
    override suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant) = Unit
}
