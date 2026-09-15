package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class PersistenceIntegrationTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var discoveries: RoomDiscoveryRepository
    private lateinit var documents: RoomDocumentRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao())
        discoveries = RoomDiscoveryRepository(database.discoveryDao())
        documents = RoomDocumentRepository(database.documentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `source and cursor survive repository round trip`() = runBlocking {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val source = source(id = "source-1", nextCheckAt = now.minusSeconds(60))
        val cursor = SourceCursor(
            sourceId = source.id,
            etag = "etag-1",
            lastModified = "Mon, 15 Sep 2026 08:00:00 GMT",
            opaqueCursor = "page-2",
            lastGuid = "guid-10",
            lastCheckedAt = now,
        )

        sources.upsert(source)
        sources.upsertCursor(cursor)

        assertEquals(source, sources.findById(source.id))
        assertEquals(cursor, sources.findCursor(source.id))
        assertNull(sources.findById(SourceId("missing")))
    }

    @Test
    fun `find due returns only enabled sources whose check time has arrived`() = runBlocking {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val due = source(id = "due", nextCheckAt = now.minusSeconds(1))
        val neverChecked = source(id = "never", nextCheckAt = null)
        val future = source(id = "future", nextCheckAt = now.plusSeconds(3600))
        val disabled = source(id = "disabled", nextCheckAt = now.minusSeconds(1), enabled = false)

        listOf(due, neverChecked, future, disabled).forEach { sources.upsert(it) }

        assertEquals(setOf(due.id, neverChecked.id), sources.findDue(now).map { it.id }.toSet())
    }

    @Test
    fun `discover is idempotent for same source and raw url`() = runBlocking {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val source = source(id = "source-1", nextCheckAt = now)
        sources.upsert(source)

        val first = DiscoveredItem(
            id = DiscoveredItemId("item-1"),
            sourceId = source.id,
            url = "https://example.org/post/1",
            title = "First title",
            discoveredAt = now,
        )
        val duplicate = first.copy(
            id = DiscoveredItemId("item-2"),
            title = "Duplicate title",
            discoveredAt = now.plusSeconds(5),
        )

        val storedFirst = discoveries.discover(first)
        val storedDuplicate = discoveries.discover(duplicate)

        assertEquals(first.id, storedFirst.id)
        assertEquals(first.id, storedDuplicate.id)
        assertEquals("First title", storedDuplicate.title)
        assertEquals(1, database.discoveryDao().count())
    }

    @Test
    fun `document version fingerprint persist together and duplicate version rolls transaction back`() = runBlocking {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val source = source(id = "source-1", nextCheckAt = now)
        sources.upsert(source)

        val document = Document(
            id = DocumentId("doc-1"),
            sourceId = source.id,
            canonicalUrl = "https://example.org/post/1",
            title = "Original title",
            author = "Author",
            publishedAt = now.minusSeconds(3600),
            language = "en",
            normalizedText = "Original body",
            contentHash = "hash-v1",
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val version = DocumentVersion(
            documentId = document.id,
            version = 1,
            contentHash = document.contentHash,
            normalizedText = document.normalizedText,
            fetchedAt = now,
            parserVersion = "parser-1",
        )
        val fingerprint = SeenFingerprint(
            canonicalUrlHash = "url-hash-1",
            contentHash = document.contentHash,
            sourceId = source.id,
            seenAt = now,
            disposition = ContentDisposition.SAVED,
        )

        documents.persist(document, version, fingerprint)

        assertEquals(document, documents.findById(document.id))
        assertEquals(listOf(version), documents.versions(document.id))
        assertEquals(fingerprint, documents.findFingerprint(source.id, fingerprint.canonicalUrlHash))

        val conflictingDocument = document.copy(
            title = "Must be rolled back",
            updatedAt = now.plusSeconds(60),
        )
        val conflictingVersion = version.copy(
            normalizedText = "Conflicting body",
            contentHash = "hash-conflict",
        )

        assertThrows(Exception::class.java) {
            runBlocking {
                documents.persist(conflictingDocument, conflictingVersion, null)
            }
        }

        val afterFailure = documents.findById(document.id)
        assertNotNull(afterFailure)
        assertEquals("Original title", afterFailure?.title)
        assertEquals(listOf(version), documents.versions(document.id))
    }

    private fun source(
        id: String,
        nextCheckAt: Instant?,
        enabled: Boolean = true,
    ) = Source(
        id = SourceId(id),
        name = "Source $id",
        type = SourceType.RSS,
        url = "https://example.org/$id.xml",
        enabled = enabled,
        pollPolicy = PollPolicy(Duration.ofMinutes(30)),
        adapterType = "rss",
        configurationJson = "{}",
        createdAt = Instant.parse("2026-09-15T07:00:00Z"),
        nextCheckAt = nextCheckAt,
    )
}
