package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DatabaseIntegrationTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sourceRepository: RoomSourceRepository
    private lateinit var ingestionRepository: RoomIngestionRepository
    private lateinit var knowledgeRepository: RoomKnowledgeRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sourceRepository = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        ingestionRepository = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        knowledgeRepository = RoomKnowledgeRepository(database.documentDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun dueQueryHonoursEnabledAndNextCheckTime() = runTest {
        val now = Instant.parse("2026-09-15T10:00:00Z")
        sourceRepository.upsert(source("due", true, now.minusSeconds(60)))
        sourceRepository.upsert(source("future", true, now.plusSeconds(60)))
        sourceRepository.upsert(source("disabled", false, now.minusSeconds(60)))

        val due = sourceRepository.findDue(now)

        assertEquals(listOf(SourceId("due")), due.map { it.id })
    }

    @Test
    fun discoveredItemAndRawContentRoundTripThroughRealSqlite() = runTest {
        val now = Instant.parse("2026-09-15T10:00:00Z")
        val source = source("source", true, now)
        sourceRepository.upsert(source)
        val item = DiscoveredItem(
            id = DiscoveredItemId("item-1"),
            sourceId = source.id,
            url = "https://example.test/a",
            discoveredAt = now,
        )
        val bytes = byteArrayOf(0, 1, 2, 0x7f, 0x80.toByte(), 0xff.toByte())
        val raw = RawContent(
            discoveredItemId = item.id,
            contentType = "application/octet-stream; x-test=1",
            payload = bytes,
            resolvedUrl = "https://cdn.example.test/final-a",
            fetchedAt = now,
            httpStatus = 200,
            expiresAt = now.plusSeconds(3600),
        )

        ingestionRepository.upsertDiscovered(item)
        ingestionRepository.storeRawContent(raw)

        assertEquals(item, ingestionRepository.findDiscovered(source.id, item.url))
        val loaded = ingestionRepository.loadRawContent(item.id)
        assertNotNull(loaded)
        assertEquals(raw.discoveredItemId, loaded?.discoveredItemId)
        assertEquals(raw.contentType, loaded?.contentType)
        assertArrayEquals(bytes, loaded?.payload)
        assertEquals(raw.resolvedUrl, loaded?.resolvedUrl)
        assertEquals(raw.fetchedAt, loaded?.fetchedAt)
        assertEquals(raw.httpStatus, loaded?.httpStatus)
        assertEquals(raw.expiresAt, loaded?.expiresAt)
    }

    @Test
    fun knowledgeTransactionPersistsRelatedRowsWithForeignKeys() = runTest {
        val now = Instant.parse("2026-09-15T10:00:00Z")
        val source = source("source", true, now)
        sourceRepository.upsert(source)
        val document = Document(
            id = DocumentId("doc-1"),
            canonicalUrl = "https://example.test/article",
            title = "Article",
            normalizedText = "Body",
            contentHash = "hash-v1",
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val version = DocumentVersion(document.id, 1, "hash-v1", "Body", now, "parser-v1")
        val provenance = DocumentProvenance(
            documentId = document.id,
            sourceId = source.id,
            discoveredUrl = document.canonicalUrl,
            resolvedUrl = document.canonicalUrl,
            discoveredAt = now,
            fetchedAt = now,
            sourceNameSnapshot = source.name,
            sourceUrlSnapshot = source.url,
            sourceTypeSnapshot = source.type.name,
        )
        val fingerprint = SeenFingerprint("url-hash", "hash-v1", source.id, now, ContentDisposition.SAVED)

        knowledgeRepository.persist(document, version, provenance, fingerprint)

        assertEquals(document, knowledgeRepository.findByCanonicalUrl(document.canonicalUrl))
        assertEquals(listOf(version), knowledgeRepository.versions(document.id))
        assertEquals(listOf(provenance), knowledgeRepository.provenance(document.id))
        assertEquals(fingerprint, knowledgeRepository.findSeen("url-hash", source.id))
        assertNull(knowledgeRepository.findById(DocumentId("missing")))
    }

    private fun source(id: String, enabled: Boolean, nextCheckAt: Instant) = Source(
        id = SourceId(id),
        name = id,
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = enabled,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss",
        createdAt = Instant.parse("2026-09-15T08:00:00Z"),
        nextCheckAt = nextCheckAt,
    )
}
