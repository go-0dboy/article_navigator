package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.room3.withWriteTransaction
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
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExplicitTransactionIntegrationTest {
    private val now = Instant.parse("2026-09-16T10:30:00Z")

    @Test
    fun explicitWriteTransactionUpdatesExistingDocumentWhileLibraryObserverIsActive() = runBlocking {
        withDatabaseFile("library-explicit-write") { file ->
            withOpenDatabase(file) { db ->
                val source = Source(
                    id = SourceId("explicit-source"),
                    name = "Explicit transaction source",
                    type = SourceType.RSS,
                    url = "https://example.test/explicit.xml",
                    enabled = true,
                    pollPolicy = PollPolicy(Duration.ofHours(1)),
                    adapterType = "rss-atom",
                    createdAt = now.minusSeconds(3600),
                    nextCheckAt = now,
                )
                val sources = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
                val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
                val inboxRepository = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
                val knowledge = RoomKnowledgeRepository(db.documentDao())
                val library = RoomLibraryRepository(db.libraryReadDao())
                val lifecycle = db.inboxLifecycleDao()
                sources.upsert(source)

                val document = Document(
                    id = DocumentId("explicit-document"),
                    canonicalUrl = "https://example.test/explicit/article",
                    title = "Before explicit save",
                    normalizedText = "Before explicit body",
                    contentHash = "explicit-v1",
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
                        discoveredUrl = "${document.canonicalUrl}?old-origin=1",
                        resolvedUrl = document.canonicalUrl,
                        discoveredAt = now.minusSeconds(30),
                        fetchedAt = now.minusSeconds(20),
                        sourceNameSnapshot = source.name,
                        sourceUrlSnapshot = source.url,
                        sourceTypeSnapshot = source.type.name,
                    ),
                    fingerprint = SeenFingerprint(
                        canonicalUrlHash = sha256(document.canonicalUrl),
                        contentHash = document.contentHash,
                        sourceId = source.id,
                        seenAt = now,
                        disposition = ContentDisposition.SAVED,
                    ),
                )

                val discovery = DiscoveredItem(
                    id = DiscoveredItemId("explicit-discovery"),
                    sourceId = source.id,
                    url = document.canonicalUrl,
                    canonicalUrl = document.canonicalUrl,
                    resolvedUrl = document.canonicalUrl,
                    title = "After explicit save",
                    discoveredAt = now.plusSeconds(10),
                    status = DiscoveryStatus.FETCHED,
                )
                ingestion.upsertDiscovered(discovery)
                val update = InboxItem(
                    id = InboxItemId("explicit-inbox"),
                    canonicalUrl = document.canonicalUrl,
                    title = "After explicit save",
                    normalizedText = "After explicit body",
                    contentHash = "explicit-v2",
                    createdAt = now.plusSeconds(20),
                    updatedAt = now.plusSeconds(20),
                    parserVersion = "default-content-extractor-v2",
                )
                inboxRepository.put(
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
                val observer = launch {
                    var index = 0
                    library.observeRevision().take(2).collect { revision ->
                        if (index++ == 0) firstEmission.complete(revision) else secondEmission.complete(revision)
                    }
                }
                val beforeRevision = withTimeout(5_000) { firstEmission.await() }

                db.withWriteTransaction {
                    val inbox = checkNotNull(lifecycle.item(update.id.value))
                    val currentOrigins = lifecycle.origins(update.id.value)
                    check(currentOrigins.isNotEmpty())
                    val existing = checkNotNull(
                        lifecycle.documentByCanonicalUrl(inbox.canonicalUrl)
                            ?: lifecycle.documentByContentHash(inbox.contentHash),
                    )
                    check(
                        lifecycle.updateDocument(
                            existing.copy(
                                canonicalUrl = inbox.canonicalUrl,
                                title = inbox.title,
                                publishedAtEpochMillis = inbox.publishedAtEpochMillis,
                                normalizedText = inbox.normalizedText,
                                contentHash = inbox.contentHash,
                                updatedAtEpochMillis = now.plusSeconds(30).toEpochMilli(),
                                disposition = "SAVED",
                            ),
                        ) == 1,
                    )
                    lifecycle.upsertVersion(
                        DocumentVersionEntity(
                            documentId = existing.id,
                            version = lifecycle.maxVersion(existing.id) + 1,
                            contentHash = inbox.contentHash,
                            normalizedText = inbox.normalizedText,
                            fetchedAtEpochMillis = currentOrigins.maxOf { it.fetchedAtEpochMillis },
                            parserVersion = inbox.parserVersion,
                        ),
                    )
                    lifecycle.upsertProvenances(
                        currentOrigins.map { origin ->
                            DocumentProvenanceEntity(
                                documentId = existing.id,
                                originKey = "${origin.sourceId}|${origin.discoveredUrl}",
                                sourceId = origin.sourceId,
                                discoveredUrl = origin.discoveredUrl,
                                resolvedUrl = origin.resolvedUrl,
                                discoveredAtEpochMillis = origin.discoveredAtEpochMillis,
                                fetchedAtEpochMillis = origin.fetchedAtEpochMillis,
                                sourceNameSnapshot = origin.sourceNameSnapshot,
                                sourceUrlSnapshot = origin.sourceUrlSnapshot,
                                sourceTypeSnapshot = origin.sourceTypeSnapshot,
                            )
                        },
                    )
                    lifecycle.upsertFingerprints(
                        currentOrigins.map { origin ->
                            SeenFingerprintEntity(
                                canonicalUrlHash = sha256(origin.canonicalUrl),
                                contentHash = inbox.contentHash,
                                sourceId = origin.sourceId,
                                seenAtEpochMillis = now.plusSeconds(30).toEpochMilli(),
                                disposition = "SAVED",
                            )
                        }.distinctBy { it.canonicalUrlHash to it.sourceId },
                    )
                    val discoveryIds = currentOrigins.map { it.discoveredItemId }
                    lifecycle.finishDiscoveries(discoveryIds)
                    lifecycle.deleteRaw(discoveryIds)
                    check(lifecycle.deleteInbox(update.id.value) == 1)
                }

                val afterRevision = withTimeout(5_000) { secondEmission.await() }
                observer.join()
                assertTrue(afterRevision != beforeRevision)
                assertNull(lifecycle.item(update.id.value))
                assertEquals(2, db.documentDao().versions(document.id.value).size)
                assertEquals(2, db.documentDao().provenance(document.id.value).size)
                val saved = checkNotNull(db.documentDao().findById(document.id.value))
                assertEquals("After explicit save", saved.title)
                assertEquals("After explicit body", saved.normalizedText)
            }
        }
    }

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
