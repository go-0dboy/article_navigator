package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryWriteTransactionProbeTest {
    @Test
    fun emptyWriteTransactionStartsWhileSavedCountObserverIsActive() = runBlocking {
        withDatabaseFile("empty-write") { file ->
            withOpenDatabase(file) { db ->
                seedSavedDocument(db, "empty")
                val library = RoomLibraryRepository(db.libraryReadDao())
                val firstEmission = CompletableDeferred<Unit>()
                val observer = launch {
                    library.observeSavedCount().collect { firstEmission.complete(Unit) }
                }
                withTimeout(5_000) { firstEmission.await() }

                db.withWriteTransaction { Unit }

                observer.cancelAndJoin()
            }
        }
    }

    @Test
    fun singleUpdateInsideWriteTransactionRunsWhileSavedCountObserverIsActive() = runBlocking {
        withDatabaseFile("single-update-write") { file ->
            withOpenDatabase(file) { db ->
                val document = seedSavedDocument(db, "update")
                val library = RoomLibraryRepository(db.libraryReadDao())
                val lifecycle = db.inboxLifecycleDao()
                val firstEmission = CompletableDeferred<Unit>()
                val observer = launch {
                    library.observeSavedCount().collect { firstEmission.complete(Unit) }
                }
                withTimeout(5_000) { firstEmission.await() }

                db.withWriteTransaction {
                    assertEquals(
                        1,
                        lifecycle.updateDocument(
                            document.copy(
                                title = "Updated inside explicit transaction",
                                updatedAtEpochMillis = 2L,
                            ),
                        ),
                    )
                }

                observer.cancelAndJoin()
            }
        }
    }

    private suspend fun seedSavedDocument(db: ArticleNavigatorDatabase, suffix: String): DocumentEntity {
        val document = DocumentEntity(
            id = "probe-$suffix",
            canonicalUrl = "https://example.test/probe/$suffix",
            title = "Probe $suffix",
            author = null,
            publishedAtEpochMillis = null,
            language = null,
            normalizedText = "Probe body $suffix",
            contentHash = "probe-hash-$suffix",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            disposition = "SAVED",
        )
        db.documentDao().upsert(document)
        return document
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
}
