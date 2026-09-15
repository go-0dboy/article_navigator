package io.github.go0dboy.articlenavigator.storage.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration2To3Test {
    @Test
    fun migrationAddsInboxRetryStatePreservesProvenanceAndSuppressesOnlyLegacySampleRows() = runTest {
        val file = Files.createTempFile("article-navigator-migration-2-3", ".db")
        Files.deleteIfExists(file)
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            createVersion2Subset(connection)
            seedVersion2Data(connection)

            MIGRATION_2_3.migrate(connection)

            val discoveredColumns = tableColumns(connection, "discovered_items")
            assertTrue("processingAttempts" in discoveredColumns)
            assertTrue("nextProcessingAtEpochMillis" in discoveredColumns)
            assertTrue("lastProcessingError" in discoveredColumns)

            assertTrue("id" in tableColumns(connection, "inbox_items"))
            assertTrue("originKey" in tableColumns(connection, "document_provenance"))

            connection.prepare(
                "SELECT documentId, originKey, sourceId, discoveredUrl FROM document_provenance WHERE documentId = 'doc-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("doc-1", statement.getText(0))
                assertEquals("source-1|https://example.test/article", statement.getText(1))
                assertEquals("source-1", statement.getText(2))
                assertEquals("https://example.test/article", statement.getText(3))
            }

            connection.prepare(
                "SELECT status, processingAttempts, nextProcessingAtEpochMillis, lastProcessingError FROM discovered_items WHERE id = 'item-1'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("DISCOVERED", statement.getText(0))
                assertEquals(0L, statement.getLong(1))
                assertTrue(statement.isNull(2))
                assertTrue(statement.isNull(3))
            }

            connection.prepare(
                "SELECT status, processingAttempts FROM discovered_items WHERE id = 'legacy-device-item'",
            ).use { statement ->
                assertTrue(statement.step())
                assertEquals("PROCESSED", statement.getText(0))
                assertEquals(0L, statement.getLong(1))
            }
        } finally {
            connection.close()
            Files.deleteIfExists(file)
        }
    }

    private fun createVersion2Subset(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE `sources` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `pollIntervalSeconds` INTEGER NOT NULL,
                `requiresUnmeteredNetwork` INTEGER NOT NULL,
                `adapterType` TEXT NOT NULL,
                `configurationJson` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `lastSuccessfulCheckAtEpochMillis` INTEGER,
                `nextCheckAtEpochMillis` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        execute(
            connection,
            """
            CREATE TABLE `discovered_items` (
                `id` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `canonicalUrl` TEXT,
                `title` TEXT,
                `publishedAtEpochMillis` INTEGER,
                `discoveredAtEpochMillis` INTEGER NOT NULL,
                `contentHash` TEXT,
                `status` TEXT NOT NULL,
                `relevanceScore` REAL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        execute(
            connection,
            """
            CREATE TABLE `documents` (
                `id` TEXT NOT NULL,
                `canonicalUrl` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `author` TEXT,
                `publishedAtEpochMillis` INTEGER,
                `language` TEXT,
                `normalizedText` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `createdAtEpochMillis` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                `disposition` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        execute(
            connection,
            """
            CREATE TABLE `document_provenance` (
                `documentId` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `discoveredUrl` TEXT NOT NULL,
                `discoveredAtEpochMillis` INTEGER NOT NULL,
                `fetchedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`documentId`, `sourceId`)
            )
            """.trimIndent(),
        )
    }

    private fun seedVersion2Data(connection: SQLiteConnection) {
        execute(
            connection,
            "INSERT INTO sources VALUES ('source-1','Source','RSS','https://example.test/feed.xml',1,3600,0,'rss-atom','{}',1,NULL,1)",
        )
        execute(
            connection,
            "INSERT INTO sources VALUES ('phase3-sample-rss','Phase 3 sample','RSS','https://example.test/device.xml',1,900,0,'rss-atom','{}',1,NULL,1)",
        )
        execute(
            connection,
            "INSERT INTO discovered_items VALUES ('item-1','source-1','https://example.test/article','https://example.test/article','Article',NULL,10,NULL,'DISCOVERED',NULL)",
        )
        execute(
            connection,
            "INSERT INTO discovered_items VALUES ('legacy-device-item','phase3-sample-rss','https://example.test/legacy','https://example.test/legacy','Legacy diagnostic',NULL,11,NULL,'DISCOVERED',NULL)",
        )
        execute(
            connection,
            "INSERT INTO documents VALUES ('doc-1','https://example.test/article','Article',NULL,NULL,NULL,'Body','hash',1,1,'SAVED')",
        )
        execute(
            connection,
            "INSERT INTO document_provenance VALUES ('doc-1','source-1','https://example.test/article',10,20)",
        )
    }

    private fun tableColumns(connection: SQLiteConnection, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            while (statement.step()) columns += statement.getText(1)
        }
        return columns
    }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
