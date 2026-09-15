package io.github.go0dboy.articlenavigator.storage.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationHardeningTest {
    @Test
    fun migration3To4PreservesOperationalInboxKnowledgeAndProvenanceData() = runTest {
        withDatabase("migration-3-4") { connection ->
            createVersion3Subset(connection)
            seedVersion3Data(connection)

            MIGRATION_3_4.migrate(connection)

            assertCoreVersion4Data(connection)
            assertEquals(0L, scalarLong(connection, "SELECT settingsRevision FROM sources WHERE id='source-1'"))
            assertTrue(isNull(connection, "SELECT leaseToken FROM sources WHERE id='source-1'"))
            assertTrue(isNull(connection, "SELECT leaseExpiresAtEpochMillis FROM sources WHERE id='source-1'"))
            assertEquals(100L, scalarLong(connection, "SELECT lastSeenAtEpochMillis FROM discovered_items WHERE id='item-1'"))
            assertTrue(isNull(connection, "SELECT resolvedUrl FROM inbox_origins WHERE discoveredItemId='item-1'"))
            assertEquals("Source One", scalarText(connection, "SELECT sourceNameSnapshot FROM document_provenance WHERE documentId='doc-1'"))
            assertEquals("https://example.test/feed.xml", scalarText(connection, "SELECT sourceUrlSnapshot FROM document_provenance WHERE documentId='doc-1'"))
            assertEquals("RSS", scalarText(connection, "SELECT sourceTypeSnapshot FROM document_provenance WHERE documentId='doc-1'"))

            assertIndexExists(connection, "index_sources_leaseExpiresAtEpochMillis")
            assertIndexExists(connection, "index_discovered_items_lastSeenAtEpochMillis")
            assertIndexExists(connection, "index_document_provenance_sourceId")
            assertIndexExists(connection, "index_seen_fingerprints_sourceId")
            assertForeignKeyDeleteAction(connection, "document_provenance", "sources", "RESTRICT")
            assertForeignKeyDeleteAction(connection, "seen_fingerprints", "sources", "RESTRICT")

            val hardDelete = runCatching { execute(connection, "DELETE FROM sources WHERE id='source-1'") }
            assertTrue("Hard delete must be rejected while durable provenance exists", hardDelete.isFailure)
            assertEquals("source-1", scalarText(connection, "SELECT sourceId FROM document_provenance WHERE documentId='doc-1'"))
        }
    }

    @Test
    fun migration1To2To3To4PreservesDataAcrossFullSupportedChain() = runTest {
        withDatabase("migration-1-4") { connection ->
            createVersion1Subset(connection)
            seedVersion1Data(connection)

            MIGRATION_1_2.migrate(connection)
            execute(
                connection,
                "INSERT INTO source_collection_states VALUES ('source-1',2,150,'Timeout','slow',3)",
            )

            MIGRATION_2_3.migrate(connection)
            execute(
                connection,
                "INSERT INTO inbox_items VALUES ('inbox-1','https://example.test/article','Inbox Article',NULL,'Inbox body','inbox-hash',200,201)",
            )
            execute(
                connection,
                "INSERT INTO inbox_origins VALUES ('inbox-1','item-1','source-1','https://example.test/article','https://example.test/article',100,180)",
            )

            MIGRATION_3_4.migrate(connection)

            assertCoreVersion4Data(connection)
            assertEquals(2L, scalarLong(connection, "SELECT consecutiveFailures FROM source_collection_states WHERE sourceId='source-1'"))
            assertEquals("etag-v1", scalarText(connection, "SELECT etag FROM source_cursors WHERE sourceId='source-1'"))
            assertEquals("DISCOVERED", scalarText(connection, "SELECT status FROM discovered_items WHERE id='item-1'"))
            assertEquals(0L, scalarLong(connection, "SELECT processingAttempts FROM discovered_items WHERE id='item-1'"))
            assertEquals(100L, scalarLong(connection, "SELECT lastSeenAtEpochMillis FROM discovered_items WHERE id='item-1'"))
            assertEquals("Inbox Article", scalarText(connection, "SELECT title FROM inbox_items WHERE id='inbox-1'"))
            assertEquals("Body", scalarText(connection, "SELECT normalizedText FROM documents WHERE id='doc-1'"))
            assertEquals("parser-v1", scalarText(connection, "SELECT parserVersion FROM document_versions WHERE documentId='doc-1'"))
            assertEquals("source-1|https://example.test/article", scalarText(connection, "SELECT originKey FROM document_provenance WHERE documentId='doc-1'"))
            assertEquals("Source One", scalarText(connection, "SELECT sourceNameSnapshot FROM document_provenance WHERE documentId='doc-1'"))
            assertEquals("SAVED", scalarText(connection, "SELECT disposition FROM seen_fingerprints WHERE sourceId='source-1'"))
            assertForeignKeyDeleteAction(connection, "document_provenance", "sources", "RESTRICT")
            assertIndexExists(connection, "index_sources_leaseExpiresAtEpochMillis")
            assertIndexExists(connection, "index_discovered_items_lastSeenAtEpochMillis")
        }
    }

    private fun assertCoreVersion4Data(connection: SQLiteConnection) {
        assertEquals("Source One", scalarText(connection, "SELECT name FROM sources WHERE id='source-1'"))
        assertEquals("etag-v1", scalarText(connection, "SELECT etag FROM source_cursors WHERE sourceId='source-1'"))
        assertEquals(2L, scalarLong(connection, "SELECT consecutiveFailures FROM source_collection_states WHERE sourceId='source-1'"))
        assertEquals("https://example.test/article", scalarText(connection, "SELECT url FROM discovered_items WHERE id='item-1'"))
        assertEquals("Inbox Article", scalarText(connection, "SELECT title FROM inbox_items WHERE id='inbox-1'"))
        assertEquals("doc-hash", scalarText(connection, "SELECT contentHash FROM documents WHERE id='doc-1'"))
        assertEquals("source-1", scalarText(connection, "SELECT sourceId FROM document_provenance WHERE documentId='doc-1'"))
        assertEquals("url-hash", scalarText(connection, "SELECT canonicalUrlHash FROM seen_fingerprints WHERE sourceId='source-1'"))
    }

    private fun createVersion1Subset(connection: SQLiteConnection) {
        execute(connection, "PRAGMA foreign_keys=ON")
        createSources(connection)
        createSourceCursors(connection)
        execute(
            connection,
            """
            CREATE TABLE discovered_items (
                id TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                url TEXT NOT NULL,
                canonicalUrl TEXT,
                title TEXT,
                publishedAtEpochMillis INTEGER,
                discoveredAtEpochMillis INTEGER NOT NULL,
                contentHash TEXT,
                status TEXT NOT NULL,
                relevanceScore REAL,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createDocuments(connection)
        createDocumentVersions(connection)
        execute(
            connection,
            """
            CREATE TABLE document_provenance (
                documentId TEXT NOT NULL,
                sourceId TEXT NOT NULL,
                discoveredUrl TEXT NOT NULL,
                discoveredAtEpochMillis INTEGER NOT NULL,
                fetchedAtEpochMillis INTEGER NOT NULL,
                PRIMARY KEY(documentId, sourceId),
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createSeenFingerprints(connection)
    }

    private fun seedVersion1Data(connection: SQLiteConnection) {
        insertSource(connection)
        execute(connection, "INSERT INTO source_cursors VALUES ('source-1','etag-v1','last-modified','opaque','guid',90)")
        execute(
            connection,
            "INSERT INTO discovered_items VALUES ('item-1','source-1','https://example.test/article','https://example.test/article','Article',NULL,100,NULL,'DISCOVERED',0.5)",
        )
        insertDocument(connection)
        execute(connection, "INSERT INTO document_versions VALUES ('doc-1',1,'doc-hash','Body',180,'parser-v1')")
        execute(
            connection,
            "INSERT INTO document_provenance VALUES ('doc-1','source-1','https://example.test/article',100,180)",
        )
        execute(connection, "INSERT INTO seen_fingerprints VALUES ('url-hash','doc-hash','source-1',180,'SAVED')")
    }

    private fun createVersion3Subset(connection: SQLiteConnection) {
        execute(connection, "PRAGMA foreign_keys=ON")
        createSources(connection)
        execute(connection, "CREATE INDEX index_sources_nextCheckAtEpochMillis ON sources(nextCheckAtEpochMillis)")
        createSourceCursors(connection)
        execute(
            connection,
            """
            CREATE TABLE source_collection_states (
                sourceId TEXT NOT NULL PRIMARY KEY,
                consecutiveFailures INTEGER NOT NULL,
                lastAttemptAtEpochMillis INTEGER,
                lastErrorType TEXT,
                lastErrorMessage TEXT,
                lastDiscoveredCount INTEGER NOT NULL,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execute(
            connection,
            """
            CREATE TABLE discovered_items (
                id TEXT NOT NULL PRIMARY KEY,
                sourceId TEXT NOT NULL,
                url TEXT NOT NULL,
                canonicalUrl TEXT,
                title TEXT,
                publishedAtEpochMillis INTEGER,
                discoveredAtEpochMillis INTEGER NOT NULL,
                contentHash TEXT,
                status TEXT NOT NULL,
                relevanceScore REAL,
                processingAttempts INTEGER NOT NULL DEFAULT 0,
                nextProcessingAtEpochMillis INTEGER,
                lastProcessingError TEXT,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execute(connection, "CREATE INDEX index_discovered_items_sourceId ON discovered_items(sourceId)")
        execute(connection, "CREATE UNIQUE INDEX index_discovered_items_sourceId_url ON discovered_items(sourceId,url)")
        execute(connection, "CREATE INDEX index_discovered_items_status ON discovered_items(status)")
        execute(connection, "CREATE INDEX index_discovered_items_nextProcessingAtEpochMillis ON discovered_items(nextProcessingAtEpochMillis)")
        execute(
            connection,
            """
            CREATE TABLE inbox_items (
                id TEXT NOT NULL PRIMARY KEY,
                canonicalUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                publishedAtEpochMillis INTEGER,
                normalizedText TEXT NOT NULL,
                contentHash TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        execute(connection, "CREATE UNIQUE INDEX index_inbox_items_canonicalUrl ON inbox_items(canonicalUrl)")
        execute(connection, "CREATE INDEX index_inbox_items_contentHash ON inbox_items(contentHash)")
        execute(connection, "CREATE INDEX index_inbox_items_createdAtEpochMillis ON inbox_items(createdAtEpochMillis)")
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
        execute(connection, "CREATE UNIQUE INDEX index_inbox_origins_discoveredItemId ON inbox_origins(discoveredItemId)")
        execute(connection, "CREATE INDEX index_inbox_origins_sourceId ON inbox_origins(sourceId)")
        createDocuments(connection)
        execute(connection, "CREATE UNIQUE INDEX index_documents_canonicalUrl ON documents(canonicalUrl)")
        execute(connection, "CREATE INDEX index_documents_contentHash ON documents(contentHash)")
        createDocumentVersions(connection)
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
                PRIMARY KEY(documentId, originKey),
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execute(connection, "CREATE INDEX index_document_provenance_sourceId ON document_provenance(sourceId)")
        createSeenFingerprints(connection)
        execute(connection, "CREATE INDEX index_seen_fingerprints_sourceId ON seen_fingerprints(sourceId)")
    }

    private fun seedVersion3Data(connection: SQLiteConnection) {
        insertSource(connection)
        execute(connection, "INSERT INTO source_cursors VALUES ('source-1','etag-v1','last-modified','opaque','guid',90)")
        execute(connection, "INSERT INTO source_collection_states VALUES ('source-1',2,150,'Timeout','slow',3)")
        execute(
            connection,
            "INSERT INTO discovered_items VALUES ('item-1','source-1','https://example.test/article','https://example.test/article','Article',NULL,100,'raw-hash','PROCESSED',0.5,2,NULL,NULL)",
        )
        execute(
            connection,
            "INSERT INTO inbox_items VALUES ('inbox-1','https://example.test/article','Inbox Article',NULL,'Inbox body','inbox-hash',200,201)",
        )
        execute(
            connection,
            "INSERT INTO inbox_origins VALUES ('inbox-1','item-1','source-1','https://example.test/article','https://example.test/article',100,180)",
        )
        insertDocument(connection)
        execute(connection, "INSERT INTO document_versions VALUES ('doc-1',1,'doc-hash','Body',180,'parser-v1')")
        execute(
            connection,
            "INSERT INTO document_provenance VALUES ('doc-1','source-1|https://example.test/article','source-1','https://example.test/article',100,180)",
        )
        execute(connection, "INSERT INTO seen_fingerprints VALUES ('url-hash','doc-hash','source-1',180,'SAVED')")
    }

    private fun createSources(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE sources (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                url TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                pollIntervalSeconds INTEGER NOT NULL,
                requiresUnmeteredNetwork INTEGER NOT NULL,
                adapterType TEXT NOT NULL,
                configurationJson TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                lastSuccessfulCheckAtEpochMillis INTEGER,
                nextCheckAtEpochMillis INTEGER
            )
            """.trimIndent(),
        )
    }

    private fun createSourceCursors(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE source_cursors (
                sourceId TEXT NOT NULL PRIMARY KEY,
                etag TEXT,
                lastModified TEXT,
                opaqueCursor TEXT,
                lastGuid TEXT,
                lastCheckedAtEpochMillis INTEGER,
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createDocuments(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE documents (
                id TEXT NOT NULL PRIMARY KEY,
                canonicalUrl TEXT NOT NULL,
                title TEXT NOT NULL,
                author TEXT,
                publishedAtEpochMillis INTEGER,
                language TEXT,
                normalizedText TEXT NOT NULL,
                contentHash TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL,
                disposition TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createDocumentVersions(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE document_versions (
                documentId TEXT NOT NULL,
                version INTEGER NOT NULL,
                contentHash TEXT NOT NULL,
                normalizedText TEXT NOT NULL,
                fetchedAtEpochMillis INTEGER NOT NULL,
                parserVersion TEXT NOT NULL,
                PRIMARY KEY(documentId, version),
                FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createSeenFingerprints(connection: SQLiteConnection) {
        execute(
            connection,
            """
            CREATE TABLE seen_fingerprints (
                canonicalUrlHash TEXT NOT NULL,
                contentHash TEXT,
                sourceId TEXT NOT NULL,
                seenAtEpochMillis INTEGER NOT NULL,
                disposition TEXT NOT NULL,
                PRIMARY KEY(canonicalUrlHash, sourceId),
                FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun insertSource(connection: SQLiteConnection) {
        execute(
            connection,
            "INSERT INTO sources VALUES ('source-1','Source One','RSS','https://example.test/feed.xml',1,3600,0,'rss-atom','{}',10,80,90)",
        )
    }

    private fun insertDocument(connection: SQLiteConnection) {
        execute(
            connection,
            "INSERT INTO documents VALUES ('doc-1','https://example.test/article','Article',NULL,NULL,NULL,'Body','doc-hash',100,180,'SAVED')",
        )
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

    private fun isNull(connection: SQLiteConnection, sql: String): Boolean =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.isNull(0)
        }

    private suspend fun withDatabase(name: String, block: suspend (SQLiteConnection) -> Unit) {
        val file = Files.createTempFile("article-navigator-$name", ".db")
        Files.deleteIfExists(file)
        val connection = BundledSQLiteDriver().open(file.toString())
        try {
            block(connection)
        } finally {
            connection.close()
            Files.deleteIfExists(file)
        }
    }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}
