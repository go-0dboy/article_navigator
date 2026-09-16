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
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFileInvalidationTest {
    private val now = Instant.parse("2026-09-16T09:30:00Z")

    @Test
    fun revisionReemitsWhenExistingDocumentChangesWithoutCountChange() = runBlocking {
        withDatabaseFile("library-invalidation") { file ->
            withOpenDatabase(file) { db ->
                val source = source()
                val sources = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
                val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
                val inbox = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
                val knowledge = RoomKnowledgeRepository(db.documentDao())
                val library = RoomLibraryRepository(db.libraryReadDao())
                sources.upsert(source)

                val document = Document(
                    id = DocumentId("file-observed-doc"),
                    canonicalUrl = "https://example.test/file-observed",
                    title = "Before",
                    publishedAt = now.minusSeconds(600),
                    normalizedText = "Before body",
                    contentHash = "file-observed-v1",
                    createdAt = now,
                    updatedAt = now,
                    disposition = ContentDisposition.SAVED,
                )
                knowledge.persist(
                    document = document,
                    version = DocumentVersion(
                        documentId = document.id,
                        version = 1,
                        contentHash = document.contentHash,
                        normalizedText = document.normalizedText,
                        fetchedAt = now,
                        parserVersion = "default-content-extractor-v2",
                    ),
                    provenance = DocumentProvenance(
                        documentId = document.id,
                        sourceId = source.id,
                        discoveredUrl = document.canonicalUrl,
                        resolvedUrl = document.canonicalUrl,
                        discoveredAt = now.minusSeconds(30),
                        fetchedAt = now.minusSeconds(20),
                        sourceNameSnapshot = source.name,
                        sourceUrlSnapshot = source.url,
                        sourceTypeSnapshot = source.type.name,
                    ),
                    fingerprint = SeenFingerprint(
                        canonicalUrlHash = "initial-file-observed-fingerprint",
                        contentHash = document.contentHash,
                        sourceId = source.id,
                        seenAt = now,
                        disposition = ContentDisposition.SAVED,
                    ),
                )

                val discovery = DiscoveredItem(
                    id = DiscoveredItemId("file-observed-discovery"),
                    sourceId = source.id,
                    url = document.canonicalUrl,
                    canonicalUrl = document.canonicalUrl,
                    resolvedUrl = document.canonicalUrl,
                    title = "After",
                    discoveredAt = now.plusSeconds(10),
                    status = DiscoveryStatus.FETCHED,
                )
                ingestion.upsertDiscovered(discovery)
                val update = InboxItem(
                    id = InboxItemId("file-observed-inbox"),
                    canonicalUrl = document.canonicalUrl,
                    title = "After",
                    normalizedText = "After body",
                    contentHash = "file-observed-v2",
                    createdAt = now.plusSeconds(20),
                    updatedAt = now.plusSeconds(20),
                    parserVersion = "default-content-extractor-v2",
                )
                inbox.put(
                    update,
                    InboxOrigin(
                        inboxItemId = update.id,
                        discoveredItemId = discovery.id,
                        sourceId = source.id,
                        discoveredUrl = discovery.url,
                        resolvedUrl = discovery.resolvedUrl,
                        canonicalUrl = checkNotNull(discovery.canonicalUrl),
                        discoveredAt = discovery.discoveredAt,
                        fetchedAt = now.plusSeconds(15),
                        sourceNameSnapshot = source.name,
                        sourceUrlSnapshot = source.url,
                        sourceTypeSnapshot = source.type.name,
                    ),
                )

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
                val savedId = try {
                    inbox.saveCurrent(update.id, now.plusSeconds(30), "ignored")
                } catch (failure: Throwable) {
                    val causes = mutableListOf<String>()
                    var current: Throwable? = failure
                    while (current != null) {
                        causes += "${current::class.qualifiedName}: ${current.message}"
                        current = current.cause
                    }
                    throw AssertionError(
                        "saveCurrent failed while observeRevision was active: ${causes.joinToString(" -> ")}",
                        failure,
                    )
                }
                assertEquals(document.id, savedId)
                val secondRevision = withTimeout(5_000) { secondEmission.await() }
                collector.join()

                assertEquals(firstRevision, secondRevision)
                assertEquals(1, library.observeSavedCount().take(1).let { flow ->
                    var count = -1
                    flow.collect { count = it }
                    count
                })
                val row = library.loadPage(null, 10).single()
                assertEquals("After", row.title)
                assertEquals("After body", row.snippet)
                assertEquals(2, db.documentDao().versions(document.id.value).size)
            }
        }
    }

    private fun source() = Source(
        id = SourceId("file-observed-source"),
        name = "Observed source",
        type = SourceType.RSS,
        url = "https://example.test/file-observed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    private fun openDatabase(file: Path): ArticleNavigatorDatabase =
        Room.databaseBuilder<ArticleNavigatorDatabase>(file.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .build()

    private suspend fun <T> withOpenDatabase(file: Path, block: suspend (ArticleNavigatorDatabase) -> T): T {
        val database = openDatabase(file)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun withDatabaseFile(name: String, block: suspend (Path) -> Unit) {
        val file = Files.createTempFile("article-navigator-$name", ".db")
        Files.deleteIfExists(file)
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(Path.of(file.toString() + "-wal"))
            Files.deleteIfExists(Path.of(file.toString() + "-shm"))
        }
    }
}
