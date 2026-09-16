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
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibraryReadIntegrationTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var ingestion: RoomIngestionRepository
    private lateinit var inbox: RoomInboxRepository
    private lateinit var knowledge: RoomKnowledgeRepository
    private lateinit var library: RoomLibraryRepository

    private val now = Instant.parse("2026-09-16T08:00:00Z")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
        knowledge = RoomKnowledgeRepository(database.documentDao())
        library = RoomLibraryRepository(database.libraryReadDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun saveCurrentAppearsInLibraryAndRejectDoesNot() = runTest {
        val source = source("source-a", "Original source")
        sources.upsert(source)

        val savedDiscovery = discovery("saved-discovery", source, "https://example.test/saved")
        ingestion.upsertDiscovered(savedDiscovery)
        val savedInbox = inboxItem("saved-inbox", savedDiscovery.url, "Saved body")
        inbox.put(savedInbox, origin(savedInbox, savedDiscovery, source))
        val savedDocumentId = checkNotNull(inbox.saveCurrent(savedInbox.id, now, "parser-v2"))

        val rejectedDiscovery = discovery("rejected-discovery", source, "https://example.test/rejected")
        ingestion.upsertDiscovered(rejectedDiscovery)
        val rejectedInbox = inboxItem("rejected-inbox", rejectedDiscovery.url, "Rejected body")
        inbox.put(rejectedInbox, origin(rejectedInbox, rejectedDiscovery, source))
        assertTrue(inbox.discardCurrent(rejectedInbox.id, ContentDisposition.REJECTED, now.plusSeconds(1)))

        assertEquals(1, library.observeSavedCount().first())
        val page = library.loadPage(after = null, limit = 10)
        assertEquals(listOf(savedDocumentId), page.map { it.id })
        assertEquals("Saved body", page.single().snippet)
        assertEquals(1, page.single().sourceCount)
        assertEquals("Original source", page.single().primarySourceName)
    }

    @Test
    fun libraryDetailUsesDurableSnapshotsAfterSourceRenameAndDisable() = runTest {
        val source = source("source-a", "Original source")
        sources.upsert(source)
        val document = savedDocument("doc-history", now, "Offline body")
        val provenance = provenance(document, source, "https://example.test/original", now.minusSeconds(30))
        persist(document, source, provenance)

        sources.upsert(source.copy(name = "Renamed source", enabled = false, url = "https://changed.test/feed"))

        val detail = checkNotNull(library.observeDocument(document.id).first())
        assertEquals("Offline body", detail.document.normalizedText)
        assertEquals("Original source", detail.provenance.single().sourceNameSnapshot)
        assertEquals("https://example.test/source-a.xml", detail.provenance.single().sourceUrlSnapshot)
        assertEquals("https://example.test/original", detail.provenance.single().discoveredUrl)
    }

    @Test
    fun detailReturnsEveryOriginAndListReportsOriginCount() = runTest {
        val first = source("source-a", "First")
        val second = source("source-b", "Second")
        sources.upsert(first)
        sources.upsert(second)
        val document = savedDocument("doc-multi", now, "Body")
        persist(document, first, provenance(document, first, "https://a.test/item", now.minusSeconds(60)))
        database.documentDao().upsertProvenance(
            provenance(document, second, "https://b.test/item", now.minusSeconds(30)).toEntity(),
        )

        val row = library.loadPage(null, 10).single()
        val detail = checkNotNull(library.observeDocument(document.id).first())

        assertEquals(2, row.sourceCount)
        assertEquals("First", row.primarySourceName)
        assertEquals(listOf("First", "Second"), detail.provenance.map { it.sourceNameSnapshot })
    }

    @Test
    fun keysetPaginationWithEqualDatesReturnsEveryDocumentExactlyOnce() = runTest {
        val source = source("source-a", "Source")
        sources.upsert(source)
        val expected = (0 until 23).map { index ->
            savedDocument("doc-${index.toString().padStart(2, '0')}", now, "Body $index")
        }
        expected.forEach { document ->
            persist(document, source, provenance(document, source, document.canonicalUrl, now.minusSeconds(1)))
        }

        val loaded = mutableListOf<DocumentId>()
        var key: LibraryPageKey? = null
        do {
            val page = library.loadPage(key, 7)
            loaded += page.map { it.id }
            key = page.lastOrNull()?.let { LibraryPageKey(it.savedAt, it.id) }
        } while (page.isNotEmpty())

        assertEquals(23, loaded.size)
        assertEquals(23, loaded.distinct().size)
        assertEquals(expected.map { it.id }.sortedByDescending { it.value }, loaded)
    }

    @Test
    fun listProjectionDoesNotExposeFullBody() = runTest {
        val source = source("source-a", "Source")
        sources.upsert(source)
        val longBody = "x".repeat(500)
        val document = savedDocument("doc-long", now, longBody)
        persist(document, source, provenance(document, source, document.canonicalUrl, now))

        val item = library.loadPage(null, 1).single()

        assertEquals(240, item.snippet.length)
        assertFalse(item.snippet == longBody)
        assertEquals(longBody, library.observeDocument(document.id).first()?.document?.normalizedText)
    }

    @Test
    fun revisionInvalidatesWhenTransactionalSaveUpdatesExistingDocumentWithoutCountChange() = runBlocking {
        val source = source("source-a", "Source")
        sources.upsert(source)
        val document = savedDocument("doc-update", now, "Original body")
        persist(document, source, provenance(document, source, document.canonicalUrl, now.minusSeconds(60)))
        assertEquals(1, library.observeSavedCount().first())

        val updateDiscovery = discovery("update-discovery", source, document.canonicalUrl)
        ingestion.upsertDiscovered(updateDiscovery)
        val updateInbox = inboxItem("update-inbox", document.canonicalUrl, "Updated body")
        inbox.put(updateInbox, origin(updateInbox, updateDiscovery, source))

        val firstEmission = CompletableDeferred<Long>()
        val secondEmission = CompletableDeferred<Long>()
        val collector = launch {
            var emissionIndex = 0
            library.observeRevision().take(2).collect { revision ->
                if (emissionIndex++ == 0) {
                    firstEmission.complete(revision)
                } else {
                    secondEmission.complete(revision)
                }
            }
        }

        val firstRevision = withTimeout(5_000) { firstEmission.await() }
        val savedId = inbox.saveCurrent(updateInbox.id, now.plusSeconds(1), "ignored-save-time-parser")
        assertEquals(document.id, savedId)

        val secondRevision = withTimeout(5_000) { secondEmission.await() }
        collector.join()

        assertEquals(1, library.observeSavedCount().first())
        val updated = library.loadPage(null, 10).single()
        assertEquals(updateInbox.title, updated.title)
        assertEquals("Updated body", updated.snippet)
        assertEquals(2, knowledge.versions(document.id).size)
        // Revision is an invalidation signal, not a monotonically increasing domain value. A table
        // change must trigger a second emission even if the scalar value happens to be unchanged.
        assertEquals(firstRevision, secondRevision)
    }

    private suspend fun persist(document: Document, source: Source, provenance: DocumentProvenance) {
        knowledge.persist(
            document = document,
            version = DocumentVersion(
                documentId = document.id,
                version = 1,
                contentHash = document.contentHash,
                normalizedText = document.normalizedText,
                fetchedAt = now,
                parserVersion = "parser-v2",
            ),
            provenance = provenance,
            fingerprint = SeenFingerprint(
                canonicalUrlHash = "url-${document.id.value}",
                contentHash = document.contentHash,
                sourceId = source.id,
                seenAt = now,
                disposition = ContentDisposition.SAVED,
            ),
        )
    }

    private fun source(id: String, name: String) = Source(
        id = SourceId(id),
        name = name,
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    private fun savedDocument(id: String, savedAt: Instant, body: String) = Document(
        id = DocumentId(id),
        canonicalUrl = "https://example.test/$id",
        title = "Title $id",
        publishedAt = now.minusSeconds(600),
        normalizedText = body,
        contentHash = "hash-$id",
        createdAt = savedAt,
        updatedAt = savedAt,
        disposition = ContentDisposition.SAVED,
    )

    private fun provenance(document: Document, source: Source, discoveredUrl: String, discoveredAt: Instant) =
        DocumentProvenance(
            documentId = document.id,
            sourceId = source.id,
            discoveredUrl = discoveredUrl,
            resolvedUrl = "$discoveredUrl?resolved=1",
            discoveredAt = discoveredAt,
            fetchedAt = discoveredAt.plusSeconds(5),
            sourceNameSnapshot = source.name,
            sourceUrlSnapshot = source.url,
            sourceTypeSnapshot = source.type.name,
        )

    private fun discovery(id: String, source: Source, url: String) = DiscoveredItem(
        id = DiscoveredItemId(id),
        sourceId = source.id,
        url = url,
        canonicalUrl = url,
        resolvedUrl = "$url?resolved=1",
        title = "Discovered $id",
        discoveredAt = now.minusSeconds(30),
        status = DiscoveryStatus.FETCHED,
    )

    private fun inboxItem(id: String, url: String, body: String) = InboxItem(
        id = InboxItemId(id),
        canonicalUrl = url,
        title = "Inbox $id",
        normalizedText = body,
        contentHash = "content-$id",
        createdAt = now,
        updatedAt = now,
    )

    private fun origin(item: InboxItem, discovery: DiscoveredItem, source: Source) = InboxOrigin(
        inboxItemId = item.id,
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
}
