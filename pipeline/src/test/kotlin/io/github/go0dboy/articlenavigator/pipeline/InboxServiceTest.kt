package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `save delegates one atomic lifecycle operation with current time and parser version`() = runTest {
        val expectedDocument = DocumentId("doc-existing-or-new")
        val repository = CapturingInboxRepository(item).apply { saveResult = expectedDocument }
        val service = InboxService(repository, clock)

        val actual = service.save(item.id)

        assertEquals(expectedDocument, actual)
        assertEquals(SaveCurrentCall(item.id, now, InboxService.PARSER_VERSION), repository.saveCurrentCall)
    }

    @Test
    fun `save does not report success when inbox was already consumed`() = runTest {
        val repository = CapturingInboxRepository(item).apply { saveResult = null }
        val service = InboxService(repository, clock)

        assertThrows(NoSuchElementException::class.java) {
            kotlinx.coroutines.runBlocking { service.save(item.id) }
        }
        assertEquals(SaveCurrentCall(item.id, now, InboxService.PARSER_VERSION), repository.saveCurrentCall)
    }

    @Test
    fun `reject and read-discard surface already handled result`() = runTest {
        val repository = CapturingInboxRepository(item)
        val service = InboxService(repository, clock)

        repository.discardResult = true
        assertTrue(service.reject(item.id))
        assertEquals(DiscardCurrentCall(item.id, ContentDisposition.REJECTED, now), repository.discardCurrentCall)

        repository.discardResult = false
        assertFalse(service.readAndDiscard(item.id))
        assertEquals(
            DiscardCurrentCall(item.id, ContentDisposition.READ_AND_DISCARDED, now),
            repository.discardCurrentCall,
        )
    }
}

private class CapturingInboxRepository(
    private val item: InboxItem,
) : InboxRepository {
    var saveResult: DocumentId? = null
    var discardResult: Boolean = false
    var saveCurrentCall: SaveCurrentCall? = null
    var discardCurrentCall: DiscardCurrentCall? = null

    override suspend fun saveCurrent(id: InboxItemId, at: Instant, parserVersion: String): DocumentId? {
        saveCurrentCall = SaveCurrentCall(id, at, parserVersion)
        return saveResult
    }

    override suspend fun discardCurrent(id: InboxItemId, disposition: ContentDisposition, at: Instant): Boolean {
        discardCurrentCall = DiscardCurrentCall(id, disposition, at)
        return discardResult
    }

    override suspend fun put(item: InboxItem, origin: InboxOrigin) = error("not used")
    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) = error("not used")
    override suspend fun listPending(limit: Int): List<InboxItem> = listOf(item).take(limit)
    override suspend fun findById(id: InboxItemId): InboxItem? = item.takeIf { it.id == id }
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? =
        item.takeIf { it.canonicalUrl == canonicalUrl }
    override suspend fun findByContentHash(contentHash: String): InboxItem? =
        item.takeIf { it.contentHash == contentHash }
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> = emptyList()
    override suspend fun discard(
        id: InboxItemId,
        disposition: ContentDisposition,
        fingerprints: List<SeenFingerprint>,
    ) = error("legacy discard must not be used by InboxService")
    override suspend fun save(
        id: InboxItemId,
        document: Document,
        version: DocumentVersion,
        provenances: List<DocumentProvenance>,
        fingerprints: List<SeenFingerprint>,
    ) = error("legacy save must not be used by InboxService")
}

private data class SaveCurrentCall(
    val id: InboxItemId,
    val at: Instant,
    val parserVersion: String,
)

private data class DiscardCurrentCall(
    val id: InboxItemId,
    val disposition: ContentDisposition,
    val at: Instant,
)
