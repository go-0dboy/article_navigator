package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
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
        val bytes = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())
        ingestion.storeRawContent(
            RawContent(
                discoveredItemId = discovery.id,
                contentType = "application/octet-stream",
                payload = bytes,
                resolvedUrl = "https://cdn.example.test/final-a",
                fetchedAt = now,
                httpStatus = 200,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        assertArrayEquals(bytes, ingestion.loadRawContent(discovery.id)?.payload)
        val item = inboxItem("inbox-1", discovery.url)
        val origin = origin(item.id, discovery)

        inbox.put(item, origin)

        assertEquals(item, inbox.findById(item.id))
        assertEquals(listOf(origin), inbox.origins(item.id))
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertNull(ingestion.loadRawContent(discovery.id))
    }

    @Test
    fun rejectAtomicallyConsumesInboxAndRetainsEveryOriginFingerprint() = runTest {
        sources.upsert(source)
        val discovery = discovery("item-2", "https://example.test/reject")
        ingestion.upsertDiscovered(discovery)
        val item = inboxItem("inbox-2", discovery.url)
        inbox.put(item, origin(item.id, discovery))

        assertTrue(inbox.discardCurrent(item.id, ContentDisposition.REJECTED, now))

        assertNull(inbox.findById(item.id))
        val fingerprint = knowledge.findSeen(sha256(item.canonicalUrl), source.id)
        assertNotNull(fingerprint)
        assertEquals(item.contentHash, fingerprint?.contentHash)
        assertEquals(ContentDisposition.REJECTED, fingerprint?.disposition)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertFalse(inbox.discardCurrent(item.id, ContentDisposition.REJECTED, now.plusSeconds(1)))
    }

    @Test
    fun saveCurrentAtomicallyPromotesInboxContentAndIngestionSnapshotIntoKnowledge() = runTest {
        sources.upsert(source)
        val discovery = discovery("item-3", "https://example.test/save")
        ingestion.upsertDiscovered(discovery)
        val item = inboxItem("inbox-3", discovery.url)
        val itemOrigin = origin(item.id, discovery)
        inbox.put(item, itemOrigin)

        val documentId = inbox.saveCurrent(item.id, now, "parser-v1")

        assertNotNull(documentId)
        assertNull(inbox.findById(item.id))
        val document = knowledge.findById(documentId!!)
        assertNotNull(document)
        assertEquals(ContentDisposition.SAVED, document?.disposition)
        assertEquals(item.canonicalUrl, document?.canonicalUrl)
        assertEquals(item.contentHash, document?.contentHash)

        val versions = knowledge.versions(documentId)
        assertEquals(1, versions.size)
        assertEquals(item.normalizedText, versions.single().normalizedText)
        assertEquals("parser-v1", versions.single().parserVersion)

        val provenances = knowledge.provenance(documentId)
        assertEquals(1, provenances.size)
        assertEquals(source.id, provenances.single().sourceId)
        assertEquals(source.name, provenances.single().sourceNameSnapshot)
        assertEquals(source.url, provenances.single().sourceUrlSnapshot)
        assertEquals(source.type.name, provenances.single().sourceTypeSnapshot)
        assertEquals(discovery.url, provenances.single().discoveredUrl)

        val fingerprint = knowledge.findSeen(sha256(item.canonicalUrl), source.id)
        assertNotNull(fingerprint)
        assertEquals(ContentDisposition.SAVED, fingerprint?.disposition)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovery.id)?.status)
        assertNull(ingestion.loadRawContent(discovery.id))
        assertNull(inbox.saveCurrent(item.id, now.plusSeconds(1), "parser-v1"))
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
        sourceNameSnapshot = source.name,
        sourceUrlSnapshot = source.url,
        sourceTypeSnapshot = source.type.name,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
