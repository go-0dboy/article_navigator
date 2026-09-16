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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFilePersistenceTest {
    private val savedAt = Instant.parse("2026-09-16T09:15:00Z")

    @Test
    fun savedDocumentAndAllProvenanceRemainReadableAfterDatabaseReopen() = runTest {
        withDatabaseFile("library") { file ->
            val first = source("source-a", "Первый источник")
            val second = source("source-b", "Второй источник")
            val document = Document(
                id = DocumentId("offline-doc"),
                canonicalUrl = "https://example.test/offline",
                title = "Офлайн материал",
                publishedAt = savedAt.minusSeconds(3600),
                normalizedText = "Полный сохранённый текст для чтения без сети.",
                contentHash = "offline-hash",
                createdAt = savedAt,
                updatedAt = savedAt,
                disposition = ContentDisposition.SAVED,
            )

            withOpenDatabase(file) { db ->
                val sourceRepo = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
                sourceRepo.upsert(first)
                sourceRepo.upsert(second)
                val dao = db.documentDao()
                dao.upsert(document.toEntity())
                dao.upsertVersion(
                    DocumentVersion(
                        documentId = document.id,
                        version = 1,
                        contentHash = document.contentHash,
                        normalizedText = document.normalizedText,
                        fetchedAt = savedAt.minusSeconds(20),
                        parserVersion = "default-content-extractor-v2",
                    ).toEntity(),
                )
                dao.upsertProvenance(provenance(document, first, "https://a.test/original", "https://a.test/final").toEntity())
                dao.upsertProvenance(provenance(document, second, "https://b.test/original", null).toEntity())
            }

            withOpenDatabase(file) { db ->
                val repository = RoomLibraryRepository(db.libraryReadDao())
                val restored = checkNotNull(repository.observeDocument(document.id).first())

                assertEquals(document.title, restored.document.title)
                assertEquals(document.normalizedText, restored.document.normalizedText)
                assertEquals(document.publishedAt, restored.document.publishedAt)
                assertEquals(document.createdAt, restored.document.createdAt)
                assertEquals(2, restored.provenance.size)
                assertEquals(listOf("Первый источник", "Второй источник"), restored.provenance.map { it.sourceNameSnapshot })
                assertEquals("https://a.test/original", restored.provenance[0].discoveredUrl)
                assertEquals("https://a.test/final", restored.provenance[0].resolvedUrl)
                assertEquals("https://b.test/original", restored.provenance[1].discoveredUrl)
            }
        }
    }

    @Test
    fun existingSavedDocumentCanBeUpdatedThroughInboxAndSurvivesReopen() = runTest {
        withDatabaseFile("library-update") { file ->
            val source = source("source-update", "Источник обновления")
            val document = Document(
                id = DocumentId("offline-update-doc"),
                canonicalUrl = "https://example.test/update",
                title = "Исходный заголовок",
                publishedAt = savedAt.minusSeconds(3600),
                normalizedText = "Исходный сохранённый текст.",
                contentHash = "offline-update-hash-v1",
                createdAt = savedAt,
                updatedAt = savedAt,
                disposition = ContentDisposition.SAVED,
            )

            withOpenDatabase(file) { db ->
                val sourceRepo = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
                val ingestionRepo = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
                val inboxRepo = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
                val knowledgeRepo = RoomKnowledgeRepository(db.documentDao())
                sourceRepo.upsert(source)
                knowledgeRepo.persist(
                    document = document,
                    version = DocumentVersion(
                        documentId = document.id,
                        version = 1,
                        contentHash = document.contentHash,
                        normalizedText = document.normalizedText,
                        fetchedAt = savedAt.minusSeconds(20),
                        parserVersion = "default-content-extractor-v2",
                    ),
                    provenance = provenance(
                        document = document,
                        source = source,
                        discoveredUrl = document.canonicalUrl,
                        resolvedUrl = document.canonicalUrl,
                    ),
                    fingerprint = SeenFingerprint(
                        canonicalUrlHash = "initial-fingerprint",
                        contentHash = document.contentHash,
                        sourceId = source.id,
                        seenAt = savedAt,
                        disposition = ContentDisposition.SAVED,
                    ),
                )

                val discovery = DiscoveredItem(
                    id = DiscoveredItemId("update-discovery"),
                    sourceId = source.id,
                    url = "${document.canonicalUrl}?utm_source=feed",
                    canonicalUrl = document.canonicalUrl,
                    resolvedUrl = document.canonicalUrl,
                    title = "Обновлённый материал",
                    discoveredAt = savedAt.plusSeconds(10),
                    status = DiscoveryStatus.FETCHED,
                )
                ingestionRepo.upsertDiscovered(discovery)
                val inboxItem = InboxItem(
                    id = InboxItemId("update-inbox"),
                    canonicalUrl = document.canonicalUrl,
                    title = "Обновлённый заголовок",
                    publishedAt = savedAt.minusSeconds(1800),
                    normalizedText = "Обновлённый сохранённый текст.",
                    contentHash = "offline-update-hash-v2",
                    createdAt = savedAt.plusSeconds(20),
                    updatedAt = savedAt.plusSeconds(20),
                    parserVersion = "default-content-extractor-v2",
                )
                inboxRepo.put(
                    inboxItem,
                    InboxOrigin(
                        inboxItemId = inboxItem.id,
                        discoveredItemId = discovery.id,
                        sourceId = source.id,
                        discoveredUrl = discovery.url,
                        resolvedUrl = discovery.resolvedUrl,
                        canonicalUrl = checkNotNull(discovery.canonicalUrl),
                        discoveredAt = discovery.discoveredAt,
                        fetchedAt = savedAt.plusSeconds(15),
                        sourceNameSnapshot = source.name,
                        sourceUrlSnapshot = source.url,
                        sourceTypeSnapshot = source.type.name,
                    ),
                )

                val savedId = inboxRepo.saveCurrent(
                    inboxItem.id,
                    savedAt.plusSeconds(30),
                    "ignored-save-time-parser",
                )
                assertEquals(document.id, savedId)
                assertEquals(1, RoomLibraryRepository(db.libraryReadDao()).observeSavedCount().first())
            }

            withOpenDatabase(file) { db ->
                val library = RoomLibraryRepository(db.libraryReadDao())
                val restored = checkNotNull(library.observeDocument(document.id).first())
                val versions = db.documentDao().versions(document.id.value)

                assertEquals(1, library.observeSavedCount().first())
                assertEquals("Обновлённый заголовок", restored.document.title)
                assertEquals("Обновлённый сохранённый текст.", restored.document.normalizedText)
                assertEquals(document.createdAt, restored.document.createdAt)
                assertEquals(savedAt.plusSeconds(30), restored.document.updatedAt)
                assertEquals(2, versions.size)
                assertEquals("Исходный сохранённый текст.", versions[0].normalizedText)
                assertEquals("Обновлённый сохранённый текст.", versions[1].normalizedText)
                assertEquals("default-content-extractor-v2", versions[1].parserVersion)
                assertEquals(2, restored.provenance.size)
                assertEquals(
                    listOf(document.canonicalUrl, "${document.canonicalUrl}?utm_source=feed"),
                    restored.provenance.map { it.discoveredUrl },
                )
            }
        }
    }

    private fun source(id: String, name: String) = Source(
        id = SourceId(id),
        name = name,
        type = SourceType.RSS,
        url = "https://example.test/$id.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = savedAt.minusSeconds(7200),
        nextCheckAt = savedAt,
    )

    private fun provenance(
        document: Document,
        source: Source,
        discoveredUrl: String,
        resolvedUrl: String?,
    ) = DocumentProvenance(
        documentId = document.id,
        sourceId = source.id,
        discoveredUrl = discoveredUrl,
        resolvedUrl = resolvedUrl,
        discoveredAt = savedAt.minusSeconds(if (source.id.value == "source-a") 60 else 30),
        fetchedAt = savedAt.minusSeconds(if (source.id.value == "source-a") 50 else 20),
        sourceNameSnapshot = source.name,
        sourceUrlSnapshot = source.url,
        sourceTypeSnapshot = source.type.name,
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
