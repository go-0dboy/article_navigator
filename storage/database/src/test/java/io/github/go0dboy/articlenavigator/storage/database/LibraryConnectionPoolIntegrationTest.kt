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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryConnectionPoolIntegrationTest {
    private val now = Instant.parse("2026-09-16T10:00:00Z")

    @Test
    fun savedCountObserverDoesNotBlockExistingDocumentSaveWithDefaultPool() = runBlocking {
        withDatabaseFile("saved-count-default") { file ->
            withOpenDatabase(file, singleConnection = false) { db ->
                val fixture = seedExistingDocumentUpdate(db, "saved-count")
                val library = RoomLibraryRepository(db.libraryReadDao())
                val firstEmission = CompletableDeferred<Unit>()
                val observer = launch {
                    library.observeSavedCount().collect {
                        firstEmission.complete(Unit)
                    }
                }

                withTimeout(5_000) { firstEmission.await() }
                assertEquals(
                    fixture.documentId,
                    fixture.inbox.saveCurrent(fixture.inboxId, now.plusSeconds(30), "ignored"),
                )
                observer.cancelAndJoin()
            }
        }
    }

    @Test
    fun revisionObserverAllowsExistingDocumentSaveWithSingleConnectionPool() = runBlocking {
        withDatabaseFile("revision-single") { file ->
            withOpenDatabase(file, singleConnection = true) { db ->
                val fixture = seedExistingDocumentUpdate(db, "revision-single")
                val library = RoomLibraryRepository(db.libraryReadDao())
                val firstEmission = CompletableDeferred<Unit>()
                val observer = launch {
                    library.observeRevision().collect {
                        firstEmission.complete(Unit)
                    }
                }

                withTimeout(5_000) { firstEmission.await() }
                assertEquals(
                    fixture.documentId,
                    fixture.inbox.saveCurrent(fixture.inboxId, now.plusSeconds(30), "ignored"),
                )
                observer.cancelAndJoin()
            }
        }
    }

    private suspend fun seedExistingDocumentUpdate(db: ArticleNavigatorDatabase, suffix: String): Fixture {
        val source = Source(
            id = SourceId("pool-source-$suffix"),
            name = "Pool source $suffix",
            type = SourceType.RSS,
            url = "https://example.test/$suffix.xml",
            enabled = true,
            pollPolicy = PollPolicy(Duration.ofHours(1)),
            adapterType = "rss-atom",
            createdAt = now.minusSeconds(3600),
            nextCheckAt = now,
        )
        val sources = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
        val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
        val inbox = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
        val knowledge = RoomKnowledgeRepository(db.documentDao())
        sources.upsert(source)

        val document = Document(
            id = DocumentId("pool-document-$suffix"),
            canonicalUrl = "https://example.test/$suffix/article",
            title = "Before $suffix",
            normalizedText = "Before body $suffix",
            contentHash = "pool-v1-$suffix",
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
                canonicalUrlHash = "pool-fingerprint-$suffix",
                contentHash = document.contentHash,
                sourceId = source.id,
                seenAt = now,
                disposition = ContentDisposition.SAVED,
            ),
        )

        val discovery = DiscoveredItem(
            id = DiscoveredItemId("pool-discovery-$suffix"),
            sourceId = source.id,
            url = document.canonicalUrl,
            canonicalUrl = document.canonicalUrl,
            resolvedUrl = document.canonicalUrl,
            title = "After $suffix",
            discoveredAt = now.plusSeconds(10),
            status = DiscoveryStatus.FETCHED,
        )
        ingestion.upsertDiscovered(discovery)
        val update = InboxItem(
            id = InboxItemId("pool-inbox-$suffix"),
            canonicalUrl = document.canonicalUrl,
            title = "After $suffix",
            normalizedText = "After body $suffix",
            contentHash = "pool-v2-$suffix",
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
        return Fixture(document.id, update.id, inbox)
    }

    private fun openDatabase(file: Path, singleConnection: Boolean): ArticleNavigatorDatabase {
        val builder = Room.databaseBuilder<ArticleNavigatorDatabase>(file.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
        if (singleConnection) {
            builder.setSingleConnectionPool()
        }
        return builder.build()
    }

    private suspend fun <T> withOpenDatabase(
        file: Path,
        singleConnection: Boolean,
        block: suspend (ArticleNavigatorDatabase) -> T,
    ): T {
        val database = openDatabase(file, singleConnection)
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

    private data class Fixture(
        val documentId: DocumentId,
        val inboxId: InboxItemId,
        val inbox: RoomInboxRepository,
    )
}
