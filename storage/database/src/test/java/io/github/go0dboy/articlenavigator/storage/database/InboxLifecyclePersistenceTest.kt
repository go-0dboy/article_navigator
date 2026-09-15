package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

class InboxLifecyclePersistenceTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var ingestion: RoomIngestionRepository
    private lateinit var inbox: RoomInboxRepository
    private lateinit var knowledge: RoomKnowledgeRepository

    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val source = Source(
        id = SourceId("source-1"),
        name = "Source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

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
    fun putMovesFetchedContentIntoInboxAndRemovesRawPayload() = runTest {
        sources.upsert(source)
        val discovery = discovery("item-1", "https://example.test/a")
        ingestion.upsertDiscovered(discovery)
        ingestion.storeRawContent(RawContent(discovery.id, "text/html", "raw", now, 200, now.plusSeconds(3600)))
        val item = inboxItem("inbox-1", discovery.url)
        val origin = origin(item.id, discovery)

        inbox.put(item, origin)

        assertEquals(item, inbox.findById(item.id))
        assertEquals(listOf(origin), inbox.origins(item.id))
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertNull(ingestion.loadRawContent(discovery.id))
    }

    @Test
    fun rejectDeletesBodyButRetainsCompactSeenFingerprint() = runTest {
        sources.upsert(source)
        val discovery = discovery("item-2", "https://example.test/reject")
        ingestion.upsertDiscovered(discovery)
        val item = inboxItem("inbox-2", discovery.url)
        inbox.put(item, origin(item.id, discovery))
        val fingerprint = SeenFingerprint(
            canonicalUrlHash = "url-hash-reject",
            contentHash = item.contentHash,
            sourceId = source.id,
            seenAt = now,
            disposition = ContentDisposition.REJECTED,
        )

        inbox.discard(item.id, ContentDisposition.REJECTED, listOf(fingerprint))

        assertNull(inbox.findById(item.id))
        assertEquals(fingerprint, knowledge.findSeen(fingerprint.canonicalUrlHash, source.id))
    }

    @Test
    fun saveAtomicallyPromotesInboxContentIntoKnowledge() = runTest {
        sources.upsert(source)
        val discovery = discovery("item-3", "https://example.test/save")
        ingestion.upsertDiscovered(discovery)
        val item = inboxItem("inbox-3", discovery.url)
        val itemOrigin = origin(item.id, discovery)
        inbox.put(item, itemOrigin)

        val document = Document(
            id = DocumentId("doc-3"),
            canonicalUrl = item.canonicalUrl,
            title = item.title,
            normalizedText = item.normalizedText,
            contentHash = item.contentHash,
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val version = DocumentVersion(document.id, 1, item.contentHash, item.normalizedText, now, "parser-v1")
        val provenance = DocumentProvenance(
            documentId = document.id,
            sourceId = source.id,
            discoveredUrl = discovery.url,
            resolvedUrl = discovery.resolvedUrl ?: discovery.url,
            discoveredAt = discovery.discoveredAt,
            fetchedAt = now,
            sourceNameSnapshot = source.name,
            sourceUrlSnapshot = source.url,
            sourceTypeSnapshot = source.type.name,
        )
        val fingerprint = SeenFingerprint("url-hash-save", item.contentHash, source.id, now, ContentDisposition.SAVED)

        inbox.save(item.id, document, version, listOf(provenance), listOf(fingerprint))

        assertNull(inbox.findById(item.id))
        assertEquals(document, knowledge.findById(document.id))
        assertEquals(listOf(version), knowledge.versions(document.id))
        assertEquals(listOf(provenance), knowledge.provenance(document.id))
        assertEquals(fingerprint, knowledge.findSeen(fingerprint.canonicalUrlHash, source.id))
    }

    private fun discovery(id: String, url: String) = DiscoveredItem(
        id = DiscoveredItemId(id),
        sourceId = source.id,
        url = url,
        canonicalUrl = url,
        resolvedUrl = url,
        title = "Article $id",
        discoveredAt = now.minusSeconds(30),
        status = DiscoveryStatus.FETCHED,
    )

    private fun inboxItem(id: String, url: String) = InboxItem(
        id = InboxItemId(id),
        canonicalUrl = url,
        title = "Inbox $id",
        normalizedText = "Durable body for $id",
        contentHash = "content-hash-$id",
        createdAt = now,
        updatedAt = now,
    )

    private fun origin(inboxId: InboxItemId, discovery: DiscoveredItem) = InboxOrigin(
        inboxItemId = inboxId,
        discoveredItemId = discovery.id,
        sourceId = source.id,
        discoveredUrl = discovery.url,
        resolvedUrl = discovery.resolvedUrl,
        canonicalUrl = discovery.canonicalUrl ?: discovery.url,
        discoveredAt = discovery.discoveredAt,
        fetchedAt = now,
    )
}
