package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import io.github.go0dboy.articlenavigator.core.model.ContentParserVersions
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration5To6Test {
    @Test
    fun existingV5InboxTextKeepsHistoricalParserVersion() = runTest {
        val databaseFile = Files.createTempFile("article-navigator-v5-v6", ".db")
        Files.deleteIfExists(databaseFile)
        val helper = MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = databaseFile.toFile(),
            driver = BundledSQLiteDriver(),
            databaseClass = ArticleNavigatorDatabase::class,
        )

        try {
            helper.createDatabase(5).also { connection ->
                execute(
                    connection,
                    "INSERT INTO inbox_items " +
                        "(id,canonicalUrl,title,publishedAtEpochMillis,normalizedText,contentHash,createdAtEpochMillis,updatedAtEpochMillis) " +
                        "VALUES ('legacy-inbox','https://example.test/legacy','Legacy',NULL,'Старый текст','legacy-hash',100,101)",
                )
                connection.close()
            }

            helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).also { connection ->
                assertEquals(
                    ContentParserVersions.DEFAULT_EXTRACTOR_V1,
                    scalarText(connection, "SELECT parserVersion FROM inbox_items WHERE id='legacy-inbox'"),
                )
                assertEquals("Старый текст", scalarText(connection, "SELECT normalizedText FROM inbox_items WHERE id='legacy-inbox'"))
                connection.close()
            }

            val database = Room.databaseBuilder<ArticleNavigatorDatabase>(databaseFile.toAbsolutePath().toString())
                .setDriver(BundledSQLiteDriver())
                .build()
            try {
                val pending = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
                    .findById(InboxItemId("legacy-inbox"))
                assertNotNull(pending)
                assertEquals(ContentParserVersions.DEFAULT_EXTRACTOR_V1, pending?.parserVersion)
                assertEquals("Старый текст", pending?.normalizedText)
            } finally {
                database.close()
            }
        } finally {
            Files.deleteIfExists(databaseFile)
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-wal"))
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-shm"))
        }
    }

    private fun scalarText(connection: SQLiteConnection, sql: String): String =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getText(0)
        }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
