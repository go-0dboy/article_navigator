package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationFullChainV5Test {
    @Test
    fun exportedVersion1MigratesThroughEverySupportedStepToCurrentRoom() = runTest {
        val databaseFile = Files.createTempFile("article-navigator-full-chain", ".db")
        Files.deleteIfExists(databaseFile)
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDirectory(),
            databasePath = databaseFile,
            driver = BundledSQLiteDriver(),
            databaseClass = ArticleNavigatorDatabase::class,
        )
        val legacyRaw = "legacy raw π Привет"

        try {
            helper.createDatabase(1).also { connection ->
                seedVersion1(connection, legacyRaw)
                connection.close()
            }

            // Stop at the historical v3 boundary so data that first became possible in v3
            // (Inbox + origin) is present before the remaining supported migrations execute.
            helper.runMigrationsAndValidate(
                3,
                listOf(MIGRATION_1_2, MIGRATION_2_3),
            ).also { connection ->
                execute(
                    connection,
                    "INSERT INTO source_collection_states VALUES ('source-1',2,150,'Timeout','slow',3)",
                )
                execute(
                    connection,
                    "INSERT INTO inbox_items VALUES ('inbox-1','https://example.test/article','Inbox Article',NULL,'Inbox body','inbox-hash',200,201)",
                )
                execute(
                    connection,
                    "INSERT INTO inbox_origins VALUES ('inbox-1','item-1','source-1','https://example.test/article','https://example.test/article',100,180)",
                )
                connection.close()
            }

            helper.runMigrationsAndValidate(
                5,
                listOf(MIGRATION_3_4, MIGRATION_4_5),
            ).also { connection ->
                assertEquals("blob", scalarText(connection, "SELECT typeof(payload) FROM raw_contents WHERE discoveredItemId='item-1'"))
                assertEquals("text/html; charset=utf-8", scalarText(connection, "SELECT contentType FROM raw_contents WHERE discoveredItemId='item-1'"))
                assertEquals("https://example.test/article", scalarText(connection, "SELECT resolvedUrl FROM raw_contents WHERE discoveredItemId='item-1'"))
                assertEquals("Source One", scalarText(connection, "SELECT sourceNameSnapshot FROM inbox_origins WHERE inboxItemId='inbox-1'"))
                assertEquals("Source One", scalarText(connection, "SELECT sourceNameSnapshot FROM document_provenance WHERE documentId='doc-1'"))
                assertEquals(2L, scalarLong(connection, "SELECT consecutiveFailures FROM source_collection_states WHERE sourceId='source-1'"))
                assertIndexExists(connection, "index_discovered_items_processingLeaseExpiresAtEpochMillis")
                assertIndexExists(connection, "index_inbox_origins_sourceId")
                assertForeignKeyDeleteAction(connection, "inbox_origins", "sources", "RESTRICT")
                assertForeignKeyDeleteAction(connection, "document_provenance", "sources", "RESTRICT")
                assertForeignKeyDeleteAction(connection, "seen_fingerprints", "sources", "RESTRICT")
                assertTrue(runCatching { execute(connection, "DELETE FROM sources WHERE id='source-1'") }.isFailure)
                connection.close()
            }

            // Schema validation by MigrationTestHelper is not enough: force the same file open
            // through the current generated Room implementation and read every durable domain.
            val database = Room.databaseBuilder<ArticleNavigatorDatabase>(databaseFile.toAbsolutePath().toString())
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
            try {
                val sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
                val ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
                val inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
                val knowledge = RoomKnowledgeRepository(database.documentDao())

                assertEquals("Source One", sources.findById(SourceId("source-1"))?.name)
                assertEquals("etag-v1", sources.loadCursor(SourceId("source-1"))?.etag)

                val discovered = ingestion.findDiscoveredById(DiscoveredItemId("item-1"))
                assertNotNull(discovered)
                val raw = ingestion.loadRawContent(DiscoveredItemId("item-1"))
                assertNotNull(raw)
                assertArrayEquals(legacyRaw.toByteArray(Charsets.UTF_8), raw?.payload)
                assertEquals("text/html; charset=utf-8", raw?.contentType)
                assertEquals("https://example.test/article", raw?.resolvedUrl)

                val pending = inbox.findById(InboxItemId("inbox-1"))
                assertNotNull(pending)
                val origin = inbox.origins(InboxItemId("inbox-1")).single()
                assertEquals(SourceId("source-1"), origin.sourceId)
                assertEquals("Source One", origin.sourceNameSnapshot)
                assertEquals("https://example.test/feed.xml", origin.sourceUrlSnapshot)
                assertEquals("RSS", origin.sourceTypeSnapshot)

                val document = knowledge.findById(DocumentId("doc-1"))
                assertNotNull(document)
                assertEquals("parser-v1", knowledge.versions(DocumentId("doc-1")).single().parserVersion)
                val provenance = knowledge.provenance(DocumentId("doc-1")).single()
                assertEquals("Source One", provenance.sourceNameSnapshot)
                assertEquals("https://example.test/feed.xml", provenance.sourceUrlSnapshot)
                assertEquals("RSS", provenance.sourceTypeSnapshot)
                assertEquals(
                    "SAVED",
                    knowledge.findSeen("url-hash", SourceId("source-1"))?.disposition?.name,
                )
            } finally {
                database.close()
            }
        } finally {
            Files.deleteIfExists(databaseFile)
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-wal"))
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-shm"))
        }
    }

    private fun schemaDirectory(): Path {
        val candidates = listOf(
            Path.of("schemas"),
            Path.of("storage", "database", "schemas"),
        )
        return candidates.firstOrNull { candidate ->
            Files.exists(
                candidate.resolve(
                    "io.github.go0dboy.articlenavigator.storage.database.ArticleNavigatorDatabase",
                ).resolve("1.json"),
            )
        } ?: error("Cannot locate exported Room schemas from ${Path.of("").toAbsolutePath()}")
    }

    private fun seedVersion1(connection: SQLiteConnection, rawText: String) {
        execute(
            connection,
            "INSERT INTO sources VALUES ('source-1','Source One','RSS','https://example.test/feed.xml',1,3600,0,'rss-atom','{}',10,80,90)",
        )
        execute(connection, "INSERT INTO source_cursors VALUES ('source-1','etag-v1','last-modified','opaque','guid',90)")
        execute(
            connection,
            "INSERT INTO discovered_items VALUES ('item-1','source-1','https://example.test/article','https://example.test/article','Article',NULL,100,'raw-hash','DISCOVERED',0.5)",
        )
        connection.prepare(
            "INSERT INTO raw_contents (discoveredItemId,mimeType,payload,fetchedAtEpochMillis,httpStatus,expiresAtEpochMillis) VALUES (?,?,?,?,?,?)",
        ).use { statement ->
            statement.bindText(1, "item-1")
            statement.bindText(2, "text/html; charset=utf-8")
            statement.bindText(3, rawText)
            statement.bindLong(4, 120)
            statement.bindLong(5, 200)
            statement.bindLong(6, 3600000)
            statement.step()
        }
        execute(
            connection,
            "INSERT INTO documents VALUES ('doc-1','https://example.test/article','Article',NULL,NULL,NULL,'Body','doc-hash',100,180,'SAVED')",
        )
        execute(connection, "INSERT INTO document_versions VALUES ('doc-1',1,'doc-hash','Body',180,'parser-v1')")
        execute(
            connection,
            "INSERT INTO document_provenance VALUES ('doc-1','source-1','https://example.test/article',100,180)",
        )
        execute(connection, "INSERT INTO seen_fingerprints VALUES ('url-hash','doc-hash','source-1',180,'SAVED')")
    }

    private fun assertIndexExists(connection: SQLiteConnection, name: String) {
        connection.prepare("SELECT name FROM sqlite_master WHERE type='index' AND name='$name'").use { statement ->
            assertTrue("Missing index $name", statement.step())
        }
    }

    private fun assertForeignKeyDeleteAction(
        connection: SQLiteConnection,
        table: String,
        referencedTable: String,
        expectedAction: String,
    ) {
        var found = false
        connection.prepare("PRAGMA foreign_key_list(`$table`)").use { statement ->
            while (statement.step()) {
                if (statement.getText(2) == referencedTable) {
                    assertEquals(expectedAction, statement.getText(6))
                    found = true
                }
            }
        }
        assertTrue("Missing FK from $table to $referencedTable", found)
    }

    private fun scalarText(connection: SQLiteConnection, sql: String): String =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getText(0)
        }

    private fun scalarLong(connection: SQLiteConnection, sql: String): Long =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getLong(0)
        }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
