package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InboxServiceTest {
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val item = InboxItem(
        id = InboxItemId("inbox-1"),
        canonicalUrl = "https://example.test/article",
        title = "Article",
        normalizedText = "Durable article body",
        contentHash = "content-hash",
        createdAt = now.minusSeconds(60),
        updatedAt = now.minusSeconds(60),
    )

    @Test
    fun `save preserves every origin and creates saved fingerprints`() = runTest {
        val repository = CapturingInboxRepository(item).apply {
            storedOrigins += origin("source-a", "discovery-a", "https://feed-a.test/article", now.minusSeconds(30))
            storedOrigins += origin("source-b", "discovery-b", "https://feed-b.test/article", now.minusSeconds(10))
        }
        val sourceRepository = FakeSourceRepository(
            source("source-a", "Feed A", "https://feed-a.test/feed.xml"),
            source("source-b", "Feed B", "https://feed-b.test/feed.xml"),
        )
        val service = InboxService(repository, sourceRepository, clock)

        val documentId = service.save(item.id)

        val saved = repository.saved
        assertNotNull(saved)
        saved!!
        assertEquals(documentId, saved.document.id)
        assertEquals(ContentDisposition.SAVED, saved.document.disposition)
        assertEquals(item.canonicalUrl, saved.document.canonicalUrl)
        assertEquals(item.contentHash, saved.document.contentHash)
        assertEquals(item.normalizedText, saved.version.normalizedText)
        assertEquals(now.minusSeconds(10), saved.version.fetchedAt)
        assertEquals(2, saved.provenances.size)
        assertEquals(setOf(SourceId("source-a"), SourceId("source-b")), saved.provenances.map { it.sourceId }.toSet())
        assertEquals(setOf("Feed A", "Feed B"), saved.provenances.map { it.sourceNameSnapshot }.toSet())
        assertEquals(setOf(SourceType.RSS.name), saved.provenances.map { it.sourceTypeSnapshot }.toSet())
        assertEquals(2, saved.fingerprints.size)
        assertEquals(setOf(ContentDisposition.SAVED), saved.fingerprints.map { it.disposition }.toSet())
        assertEquals(setOf(SourceId("source-a"), SourceId("source-b")), saved.fingerprints.map { it.sourceId }.toSet())
    }

    @Test
    fun `reject records rejection for every origin without saving document`() = runTest {
        val repository = CapturingInboxRepository(item).apply {
            storedOrigins += origin("source-a", "discovery-a", "https://feed-a.test/article", now.minusSeconds(20))
            storedOrigins += origin("source-b", "discovery-b", "https://feed-b.test/article", now.minusSeconds(10))
        }
        val sourceRepository = FakeSourceRepository(
            source("source-a", "Feed A", "https://feed-a.test/feed.xml"),
            source("source-b", "Feed B", "https://feed-b.test/feed.xml"),
        )
        val service = InboxService(repository, sourceRepository, clock)

        service.reject(item.id)

        val discarded = repository.discarded
        assertNotNull(discarded)
        discarded!!
        assertEquals(ContentDisposition.REJECTED, discarded.disposition)
        assertEquals(2, discarded.fingerprints.size)
        assertEquals(setOf(ContentDisposition.REJECTED), discarded.fingerprints.map { it.disposition }.toSet())
        assertEquals(null, repository.saved)
    }

    private fun source(id: String, name: String, url: String) = Source(
        id = SourceId(id),
        name = name,
        type = SourceType.RSS,
        url = url,
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
    )

    private fun origin(source: String, discovery: String, url: String, fetchedAt: Instant) = InboxOrigin(
        inboxItemId = item.id,
        discoveredItemId = DiscoveredItemId(discovery),
        sourceId = SourceId(source),
        discoveredUrl = url,
        resolvedUrl = url,
        canonicalUrl = url,
        discoveredAt = fetchedAt.minusSeconds(5),
        fetchedAt = fetchedAt,
    )
}

private class FakeSourceRepository(vararg sources: Source) : SourceRepository {
    private val values = sources.associateBy { it.id }

    override suspend fun upsert(source: Source) = error("not used")
    override suspend fun findById(id: SourceId): Source? = values[id]
    override suspend fun findDue(now: Instant): List<Source> = emptyList()
    override suspend fun listAll(): List<Source> = values.values.toList()
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = null
    override suspend fun saveCursor(cursor: SourceCursor) = error("not used")
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean = false
}

private class CapturingInboxRepository(
    private val item: InboxItem,
) : InboxRepository {
    val storedOrigins = mutableListOf<InboxOrigin>()
    var saved: SavedCall? = null
    var discarded: DiscardCall? = null

    override suspend fun put(item: InboxItem, origin: InboxOrigin) = error("not used")
    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) = error("not used")
    override suspend fun listPending(limit: Int): List<InboxItem> = listOf(item).take(limit)
    override suspend fun findById(id: InboxItemId): InboxItem? = item.takeIf { it.id == id }
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? =
        item.takeIf { it.canonicalUrl == canonicalUrl }
    override suspend fun findByContentHash(contentHash: String): InboxItem? =
        item.takeIf { it.contentHash == contentHash }
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> =
        if (id == item.id) storedOrigins.toList() else emptyList()

    override suspend fun discard(
        id: InboxItemId,
        disposition: ContentDisposition,
        fingerprints: List<SeenFingerprint>,
    ) {
        discarded = DiscardCall(id, disposition, fingerprints)
    }

    override suspend fun save(
        id: InboxItemId,
        document: Document,
        version: DocumentVersion,
        provenances: List<DocumentProvenance>,
        fingerprints: List<SeenFingerprint>,
    ) {
        saved = SavedCall(id, document, version, provenances, fingerprints)
    }
}

private data class SavedCall(
    val id: InboxItemId,
    val document: Document,
    val version: DocumentVersion,
    val provenances: List<DocumentProvenance>,
    val fingerprints: List<SeenFingerprint>,
)

private data class DiscardCall(
    val id: InboxItemId,
    val disposition: ContentDisposition,
    val fingerprints: List<SeenFingerprint>,
)
