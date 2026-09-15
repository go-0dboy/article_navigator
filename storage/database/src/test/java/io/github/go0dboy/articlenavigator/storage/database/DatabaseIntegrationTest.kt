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
import org.junit.Assert.assertEquals
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
        sourceRepository = RoomSourceRepository(database.sourceDao())
        ingestionRepository = RoomIngestionRepository(database.ingestionDao())
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
        val raw = RawContent(item.id, "text/html", "<article>body</article>", now, 200, now.plusSeconds(3600))

        ingestionRepository.upsertDiscovered(item)
        ingestionRepository.storeRawContent(raw)

        assertEquals(item, ingestionRepository.findDiscovered(source.id, item.url))
        assertEquals(raw, ingestionRepository.loadRawContent(item.id))
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
        val provenance = DocumentProvenance(document.id, source.id, document.canonicalUrl, now, now)
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
