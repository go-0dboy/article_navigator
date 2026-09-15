package io.github.go0dboy.articlenavigator.storage.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration1To2Test {
    @Test
    fun migrationAddsCollectionStateWithoutDestroyingSources() = runTest {
        val file = Files.createTempFile("article-navigator-migration", ".db")
        Files.deleteIfExists(file)
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            execute(connection, "CREATE TABLE `sources` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            execute(connection, "INSERT INTO `sources` (`id`) VALUES ('source-1')")

            MIGRATION_1_2.migrate(connection)

            val columns = mutableSetOf<String>()
            connection.prepare("PRAGMA table_info(`source_collection_states`)").use { statement ->
                while (statement.step()) columns += statement.getText(1)
            }
            assertEquals(
                setOf("sourceId", "consecutiveFailures", "lastAttemptAtEpochMillis", "lastErrorType", "lastErrorMessage", "lastDiscoveredCount"),
                columns,
            )
            connection.prepare("SELECT id FROM sources WHERE id = 'source-1'").use { statement ->
                assertTrue(statement.step())
                assertEquals("source-1", statement.getText(0))
            }
        } finally {
            connection.close()
            Files.deleteIfExists(file)
        }
    }

    private fun execute(connection: androidx.sqlite.SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
