package io.github.go0dboy.articlenavigator.storage.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxOriginMigrationTest {
    @Test
    fun migration3To4BackfillsInboxSourceSnapshotAndRestrictsSourceDelete() = runTest {
        val path = Files.createTempFile("inbox-origin-migration", ".db")
        val connection = BundledSQLiteDriver().open(path.toString())
        try {
            execute(connection, "PRAGMA foreign_keys=ON")
            createRequiredVersion3Tables(connection)
            execute(
                connection,
                "INSERT INTO sources(id,name,type,url) VALUES ('source-1','Original feed','RSS','https://example.test/original.xml')",
            )
            execute(connection, "INSERT INTO inbox_items(id) VALUES ('inbox-1')")
            execute(
                connection,
                "INSERT INTO inbox_origins VALUES ('inbox-1','item-1','source-1','https://example.test/article','https://example.test/article',100,180)",
            )

            MIGRATION_3_4.migrate(connection)

            assertEquals("Original feed", scalarText(connection, "SELECT sourceNameSnapshot FROM inbox_origins WHERE discoveredItemId='item-1'"))
            assertEquals("https://example.test/original.xml", scalarText(connection, "SELECT sourceUrlSnapshot FROM inbox_origins WHERE discoveredItemId='item-1'"))
            assertEquals("RSS", scalarText(connection, "SELECT sourceTypeSnapshot FROM inbox_origins WHERE discoveredItemId='item-1'"))
            assertEquals("RESTRICT", sourceDeleteAction(connection, "inbox_origins"))

            val hardDelete = runCatching { execute(connection, "DELETE FROM sources WHERE id='source-1'") }
            assertTrue("Pending Inbox provenance must prevent Source hard deletion", hardDelete.isFailure)
            assertEquals("source-1", scalarText(connection, "SELECT sourceId FROM inbox_origins WHERE discoveredItemId='item-1'"))
        } finally {
            connection.close()
            Files.deleteIfExists(path)
        }
    }

    private fun createRequiredVersion3Tables(connection: SQLiteConnection) {
        execute(connection, "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, url TEXT NOT NULL)")
        execute(connection, "CREATE TABLE discovered_items (id TEXT NOT NULL PRIMARY KEY, discoveredAtEpochMillis INTEGER NOT NULL)")
        execute(connection, "CREATE TABLE inbox_items (id TEXT NOT NULL PRIMARY KEY)")
        execute(
            connection,
            """
            CREATE TABLE inbox_origins (
                inboxItemId TEXT NOT NULL,
                discoveredItemId TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                discoveredUrl TEXT NOT NULL,
                canonicalUrl TEXT NOT NULL,
                discoveredAtEpochMillis INTEGER NOT NULL,
                fetchedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(inboxItemId, discoveredItemId),
                FOREIGN KEY(inboxItemId) REFERENCES inbox_items(id) ON DELETE CASCADE,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execute(connection, "CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY)")
        execute(
            connection,
            """
            CREATE TABLE document_provenance (
                documentId TEXT NOT NULL,
                originKey TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                discoveredUrl TEXT NOT NULL,
                discoveredAtEpochMillis INTEGER NOT NULL,
                fetchedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(documentId, originKey)
            )
            """.trimIndent(),
        )
        execute(
            connection,
            """
            CREATE TABLE seen_fingerprints (
                canonicalUrlHash TEXT NOT NULL,
                contentHash TEXT,
                sourceId TEXT NOT NULL,
                seenAtEpochMillis INTEGER NOT NULL,
                disposition TEXT NOT NULL,
                PRIMARY KEY(canonicalUrlHash, sourceId)
            )
            """.trimIndent(),
        )
    }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { statement -> statement.step() }
    }

    private fun scalarText(connection: SQLiteConnection, sql: String): String =
        connection.prepare(sql).use { statement ->
            check(statement.step()) { "Expected row for: $sql" }
            statement.getText(0)
        }

    private fun sourceDeleteAction(connection: SQLiteConnection, table: String): String =
        connection.prepare("PRAGMA foreign_key_list(`$table`)").use { statement ->
            while (statement.step()) {
                if (statement.getText(2) == "sources") return statement.getText(6)
            }
            error("No Source foreign key on $table")
        }
}
