package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.ContentParserVersions
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Migration4To5CompatibilityTest {
    @Test
    fun earlyHistoricalV4WithoutInboxSnapshotsUpgradesAndBackfillsWithoutDataLoss() = runTest {
        verifyV4Upgrade(V4Layout.EARLY)
    }

    @Test
    fun lateHistoricalV4WithInboxSnapshotsUpgradesWithoutRewritingSnapshots() = runTest {
        verifyV4Upgrade(V4Layout.LATE)
    }

    private suspend fun verifyV4Upgrade(layout: V4Layout) {
        withDatabaseFile(layout.name.lowercase()) { file ->
            BundledSQLiteDriver().open(file.toString()).use { connection ->
                createHistoricalV4(connection, layout)
                seedHistoricalV4(connection, layout)
                execute(connection, "PRAGMA user_version=4")
                execute(
                    connection,
                    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                )
                execute(
                    connection,
                    "INSERT INTO room_master_table (id,identity_hash) VALUES (42,'${layout.identityHash}')",
                )
            }

            val db = Room.databaseBuilder<ArticleNavigatorDatabase>(file.toAbsolutePath().toString())
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .build()
            try {
                // Accessing DAOs forces open + migration + Room's target-schema validation.
                val sources = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
                val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
                val inbox = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
                val knowledge = RoomKnowledgeRepository(db.documentDao())

                val source = sources.findById(SourceId("source-1"))
                assertNotNull(source)
                assertEquals("Historical Source", source?.name)

                val queued = ingestion.findDiscoveredById(DiscoveredItemId("queue-item"))
                assertNotNull(queued)
                assertEquals("https://example.test/queue-final", queued?.resolvedUrl)
                assertNull(db.articleProcessingDao().item("queue-item")?.processingLeaseToken)

                val raw = ingestion.loadRawContent(DiscoveredItemId("queue-item"))
                assertNotNull(raw)
                assertEquals("text/html; charset=utf-8", raw?.contentType)
                assertArrayEquals("<article>legacy text raw</article>".toByteArray(Charsets.UTF_8), raw?.payload)
                assertEquals("https://example.test/queue-final", raw?.resolvedUrl)

                val pending = inbox.findById(InboxItemId("inbox-1"))
                assertNotNull(pending)
                assertEquals(ContentParserVersions.DEFAULT_EXTRACTOR_V1, pending?.parserVersion)
                val origin = inbox.origins(InboxItemId("inbox-1")).single()
                assertEquals(SourceId("source-1"), origin.sourceId)
                if (layout == V4Layout.EARLY) {
                    assertEquals("Historical Source", origin.sourceNameSnapshot)
                    assertEquals("https://example.test/feed.xml", origin.sourceUrlSnapshot)
                    assertEquals("RSS", origin.sourceTypeSnapshot)
                } else {
                    assertEquals("Snapshot Name", origin.sourceNameSnapshot)
                    assertEquals("https://snapshot.example/feed", origin.sourceUrlSnapshot)
                    assertEquals("ATOM", origin.sourceTypeSnapshot)
                }

                val document = knowledge.findById(DocumentId("doc-1"))
                assertNotNull(document)
                assertEquals("doc-hash", document?.contentHash)
                assertEquals(1, knowledge.versions(DocumentId("doc-1")).size)
                assertEquals("Historical Source", knowledge.provenance(DocumentId("doc-1")).single().sourceNameSnapshot)
                assertEquals(
                    ContentDisposition.SAVED,
                    knowledge.findSeen("saved-url-hash", SourceId("source-1"))?.disposition,
                )
            } finally {
                db.close()
            }

            BundledSQLiteDriver().open(file.toString()).use { connection ->
                assertEquals(6L, scalarLong(connection, "PRAGMA user_version"))
                assertIndexExists(connection, "index_discovered_items_processingLeaseExpiresAtEpochMillis")
                assertIndexExists(connection, "index_inbox_origins_discoveredItemId")
                assertIndexExists(connection, "index_inbox_origins_sourceId")
                assertForeignKeyDeleteAction(connection, "inbox_origins", "sources", "RESTRICT")
                assertEquals(
                    ContentParserVersions.DEFAULT_EXTRACTOR_V1,
                    scalarText(connection, "SELECT parserVersion FROM inbox_items WHERE id='inbox-1'"),
                )
                assertEquals("blob", scalarText(connection, "SELECT typeof(payload) FROM raw_contents WHERE discoveredItemId='queue-item'"))
            }
        }
    }

    /**
     * Exact structural distinction represented by repository history:
     * 414598c => f998... (no Inbox snapshots, Source CASCADE),
     * 608cd67 => 7d8f... (snapshots, Source RESTRICT).
     * All other v4 tables below match the exported v4 contract shared by those builds.
     */
    private fun createHistoricalV4(connection: SQLiteConnection, layout: V4Layout) {
        execute(connection, "PRAGMA foreign_keys=ON")
        execute(connection, "CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,type TEXT NOT NULL,url TEXT NOT NULL,enabled INTEGER NOT NULL,pollIntervalSeconds INTEGER NOT NULL,requiresUnmeteredNetwork INTEGER NOT NULL,adapterType TEXT NOT NULL,configurationJson TEXT NOT NULL,createdAtEpochMillis INTEGER NOT NULL,lastSuccessfulCheckAtEpochMillis INTEGER,nextCheckAtEpochMillis INTEGER,settingsRevision INTEGER NOT NULL DEFAULT 0,leaseToken TEXT,leaseExpiresAtEpochMillis INTEGER)")
        execute(connection, "CREATE INDEX index_sources_nextCheckAtEpochMillis ON sources(nextCheckAtEpochMillis)")
        execute(connection, "CREATE INDEX index_sources_leaseExpiresAtEpochMillis ON sources(leaseExpiresAtEpochMillis)")
        execute(connection, "CREATE TABLE source_cursors (sourceId TEXT NOT NULL PRIMARY KEY,etag TEXT,lastModified TEXT,opaqueCursor TEXT,lastGuid TEXT,lastCheckedAtEpochMillis INTEGER,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE)")
        execute(connection, "CREATE TABLE source_collection_states (sourceId TEXT NOT NULL PRIMARY KEY,consecutiveFailures INTEGER NOT NULL,lastAttemptAtEpochMillis INTEGER,lastErrorType TEXT,lastErrorMessage TEXT,lastDiscoveredCount INTEGER NOT NULL,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE)")
        execute(connection, "CREATE TABLE discovered_items (id TEXT NOT NULL PRIMARY KEY,sourceId TEXT NOT NULL,url TEXT NOT NULL,canonicalUrl TEXT,resolvedUrl TEXT,title TEXT,publishedAtEpochMillis INTEGER,discoveredAtEpochMillis INTEGER NOT NULL,lastSeenAtEpochMillis INTEGER NOT NULL DEFAULT 0,contentHash TEXT,status TEXT NOT NULL,relevanceScore REAL,processingAttempts INTEGER NOT NULL DEFAULT 0,nextProcessingAtEpochMillis INTEGER,lastProcessingError TEXT,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE)")
        execute(connection, "CREATE INDEX index_discovered_items_sourceId ON discovered_items(sourceId)")
        execute(connection, "CREATE UNIQUE INDEX index_discovered_items_sourceId_url ON discovered_items(sourceId,url)")
        execute(connection, "CREATE INDEX index_discovered_items_status ON discovered_items(status)")
        execute(connection, "CREATE INDEX index_discovered_items_nextProcessingAtEpochMillis ON discovered_items(nextProcessingAtEpochMillis)")
        execute(connection, "CREATE INDEX index_discovered_items_lastSeenAtEpochMillis ON discovered_items(lastSeenAtEpochMillis)")
        execute(connection, "CREATE TABLE raw_contents (discoveredItemId TEXT NOT NULL PRIMARY KEY,mimeType TEXT,payload TEXT NOT NULL,fetchedAtEpochMillis INTEGER NOT NULL,httpStatus INTEGER NOT NULL,expiresAtEpochMillis INTEGER,FOREIGN KEY(discoveredItemId) REFERENCES discovered_items(id) ON DELETE CASCADE)")
        execute(connection, "CREATE TABLE inbox_items (id TEXT NOT NULL PRIMARY KEY,canonicalUrl TEXT NOT NULL,title TEXT NOT NULL,publishedAtEpochMillis INTEGER,normalizedText TEXT NOT NULL,contentHash TEXT NOT NULL,createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL)")
        execute(connection, "CREATE UNIQUE INDEX index_inbox_items_canonicalUrl ON inbox_items(canonicalUrl)")
        execute(connection, "CREATE INDEX index_inbox_items_contentHash ON inbox_items(contentHash)")
        execute(connection, "CREATE INDEX index_inbox_items_createdAtEpochMillis ON inbox_items(createdAtEpochMillis)")
        if (layout == V4Layout.EARLY) {
            execute(connection, "CREATE TABLE inbox_origins (inboxItemId TEXT NOT NULL,discoveredItemId TEXT NOT NULL,sourceId TEXT NOT NULL,discoveredUrl TEXT NOT NULL,resolvedUrl TEXT,canonicalUrl TEXT NOT NULL,discoveredAtEpochMillis INTEGER NOT NULL,fetchedAtEpochMillis INTEGER NOT NULL,PRIMARY KEY(inboxItemId,discoveredItemId),FOREIGN KEY(inboxItemId) REFERENCES inbox_items(id) ON DELETE CASCADE,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE CASCADE)")
        } else {
            execute(connection, "CREATE TABLE inbox_origins (inboxItemId TEXT NOT NULL,discoveredItemId TEXT NOT NULL,sourceId TEXT NOT NULL,discoveredUrl TEXT NOT NULL,resolvedUrl TEXT,canonicalUrl TEXT NOT NULL,discoveredAtEpochMillis INTEGER NOT NULL,fetchedAtEpochMillis INTEGER NOT NULL,sourceNameSnapshot TEXT NOT NULL,sourceUrlSnapshot TEXT NOT NULL,sourceTypeSnapshot TEXT NOT NULL,PRIMARY KEY(inboxItemId,discoveredItemId),FOREIGN KEY(inboxItemId) REFERENCES inbox_items(id) ON DELETE CASCADE,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE RESTRICT)")
        }
        execute(connection, "CREATE UNIQUE INDEX index_inbox_origins_discoveredItemId ON inbox_origins(discoveredItemId)")
        execute(connection, "CREATE INDEX index_inbox_origins_sourceId ON inbox_origins(sourceId)")
        execute(connection, "CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY,canonicalUrl TEXT NOT NULL,title TEXT NOT NULL,author TEXT,publishedAtEpochMillis INTEGER,language TEXT,normalizedText TEXT NOT NULL,contentHash TEXT NOT NULL,createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL,disposition TEXT NOT NULL)")
        execute(connection, "CREATE UNIQUE INDEX index_documents_canonicalUrl ON documents(canonicalUrl)")
        execute(connection, "CREATE INDEX index_documents_contentHash ON documents(contentHash)")
        execute(connection, "CREATE TABLE document_versions (documentId TEXT NOT NULL,version INTEGER NOT NULL,contentHash TEXT NOT NULL,normalizedText TEXT NOT NULL,fetchedAtEpochMillis INTEGER NOT NULL,parserVersion TEXT NOT NULL,PRIMARY KEY(documentId,version),FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE)")
        execute(connection, "CREATE INDEX index_document_versions_documentId ON document_versions(documentId)")
        execute(connection, "CREATE TABLE document_provenance (documentId TEXT NOT NULL,originKey TEXT NOT NULL,sourceId TEXT NOT NULL,discoveredUrl TEXT NOT NULL,resolvedUrl TEXT,discoveredAtEpochMillis INTEGER NOT NULL,fetchedAtEpochMillis INTEGER NOT NULL,sourceNameSnapshot TEXT NOT NULL,sourceUrlSnapshot TEXT NOT NULL,sourceTypeSnapshot TEXT NOT NULL,PRIMARY KEY(documentId,originKey),FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE,FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE RESTRICT)")
        execute(connection, "CREATE INDEX index_document_provenance_sourceId ON document_provenance(sourceId)")
        execute(connection, "CREATE TABLE seen_fingerprints (canonicalUrlHash TEXT NOT NULL,contentHash TEXT,sourceId TEXT NOT NULL,seenAtEpochMillis INTEGER NOT NULL,disposition TEXT NOT NULL,PRIMARY KEY(canonicalUrlHash,sourceId),FOREIGN KEY(sourceId) REFERENCES sources(id) ON DELETE RESTRICT)")
        execute(connection, "CREATE INDEX index_seen_fingerprints_sourceId ON seen_fingerprints(sourceId)")
        execute(connection, "CREATE TABLE interests (id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,description TEXT NOT NULL,positiveExamplesJson TEXT NOT NULL,negativeExamplesJson TEXT NOT NULL,enabled INTEGER NOT NULL,createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL)")
    }

    private fun seedHistoricalV4(connection: SQLiteConnection, layout: V4Layout) {
        execute(connection, "INSERT INTO sources VALUES ('source-1','Historical Source','RSS','https://example.test/feed.xml',1,3600,0,'rss-atom','{}',1000,NULL,2000,3,NULL,NULL)")
        execute(connection, "INSERT INTO source_cursors VALUES ('source-1','etag','lm','opaque','guid',1100)")
        execute(connection, "INSERT INTO source_collection_states VALUES ('source-1',2,1200,'Timeout','slow',4)")
        execute(connection, "INSERT INTO discovered_items VALUES ('queue-item','source-1','https://example.test/queue','https://example.test/queue','https://example.test/queue-final','Queue',NULL,1300,1350,'queue-hash','FETCHED',NULL,1,NULL,NULL)")
        execute(connection, "INSERT INTO discovered_items VALUES ('inbox-item','source-1','https://example.test/inbox','https://example.test/inbox','https://example.test/inbox-final','Inbox',NULL,1310,1360,'inbox-hash','PROCESSED',NULL,0,NULL,NULL)")
        execute(connection, "INSERT INTO raw_contents VALUES ('queue-item','text/html; charset=utf-8','<article>legacy text raw</article>',1400,200,9999999999999)")
        execute(connection, "INSERT INTO inbox_items VALUES ('inbox-1','https://example.test/inbox','Inbox',NULL,'Inbox body','inbox-hash',1500,1501)")
        if (layout == V4Layout.EARLY) {
            execute(connection, "INSERT INTO inbox_origins VALUES ('inbox-1','inbox-item','source-1','https://example.test/inbox','https://example.test/inbox-final','https://example.test/inbox',1310,1450)")
        } else {
            execute(connection, "INSERT INTO inbox_origins VALUES ('inbox-1','inbox-item','source-1','https://example.test/inbox','https://example.test/inbox-final','https://example.test/inbox',1310,1450,'Snapshot Name','https://snapshot.example/feed','ATOM')")
        }
        execute(connection, "INSERT INTO documents VALUES ('doc-1','https://example.test/saved','Saved',NULL,NULL,NULL,'Saved body','doc-hash',1600,1601,'SAVED')")
        execute(connection, "INSERT INTO document_versions VALUES ('doc-1',1,'doc-hash','Saved body',1590,'parser-v1')")
        execute(connection, "INSERT INTO document_provenance VALUES ('doc-1','source-1|https://example.test/saved','source-1','https://example.test/saved','https://example.test/saved-final',1550,1590,'Historical Source','https://example.test/feed.xml','RSS')")
        execute(connection, "INSERT INTO seen_fingerprints VALUES ('saved-url-hash','doc-hash','source-1',1600,'SAVED')")
        execute(connection, "INSERT INTO interests VALUES ('interest-1','Topic','Description','[]','[]',1,1000,1001)")
    }

    private fun assertIndexExists(connection: SQLiteConnection, name: String) {
        assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='$name'"))
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
        assertTrue("Expected FK from $table to $referencedTable", found)
    }

    private fun scalarLong(connection: SQLiteConnection, sql: String): Long =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getLong(0)
        }

    private fun scalarText(connection: SQLiteConnection, sql: String): String =
        connection.prepare(sql).use { statement ->
            assertTrue(statement.step())
            statement.getText(0)
        }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { it.step() }
    }

    private suspend fun withDatabaseFile(name: String, block: suspend (Path) -> Unit) {
        val file = Files.createTempFile("article-navigator-v4-$name", ".db")
        Files.deleteIfExists(file)
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(Path.of(file.toString() + "-wal"))
            Files.deleteIfExists(Path.of(file.toString() + "-shm"))
        }
    }

    private enum class V4Layout(val identityHash: String) {
        EARLY("f998f490efb706db46f71be8edd9dfd0"),
        LATE("7d8fa89af10b792aad5f0ba75e77eb60"),
    }
}
