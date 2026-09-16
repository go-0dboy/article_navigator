package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration6To7Test {
    @Test
    fun version6TextAndProvenanceRemainUntouchedAndOpenInCurrentRoom() = runTest {
        val databaseFile = Files.createTempFile("article-navigator-v6-v7", ".db")
        Files.deleteIfExists(databaseFile)
        val helper = MigrationTestHelper(
            instrumentation = InstrumentationRegistry.getInstrumentation(),
            file = databaseFile.toFile(),
            driver = BundledSQLiteDriver(),
            databaseClass = ArticleNavigatorDatabase::class,
        )

        try {
            helper.createDatabase(6).also { connection ->
                seedVersion6(connection)
                connection.close()
            }

            helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7)).also { connection ->
                assertEquals("Legacy inbox body", scalarText(connection, "SELECT normalizedText FROM inbox_items WHERE id='inbox-1'"))
                assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM inbox_items WHERE id='inbox-1' AND structuredContentFormat IS NULL AND structuredContent IS NULL"))
                assertEquals("Legacy saved body", scalarText(connection, "SELECT normalizedText FROM documents WHERE id='doc-1'"))
                assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM documents WHERE id='doc-1' AND structuredContentFormat IS NULL AND structuredContent IS NULL"))
                assertEquals(1L, scalarLong(connection, "SELECT COUNT(*) FROM document_versions WHERE documentId='doc-1' AND version=1 AND structuredContentFormat IS NULL AND structuredContent IS NULL"))
                assertEquals("Historical Source", scalarText(connection, "SELECT sourceNameSnapshot FROM document_provenance WHERE documentId='doc-1'"))
                connection.close()
            }

            val database = Room.databaseBuilder<ArticleNavigatorDatabase>(databaseFile.toAbsolutePath().toString())
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_6_7)
                .build()
            try {
                val inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
                val knowledge = RoomKnowledgeRepository(database.documentDao())

                val pending = inbox.findById(InboxItemId("inbox-1"))
                assertNotNull(pending)
                assertEquals("Legacy inbox body", pending?.normalizedText)
                assertNull(pending?.structuredContentFormat)
                assertNull(pending?.structuredContent)
                assertEquals("Historical Source", inbox.origins(InboxItemId("inbox-1")).single().sourceNameSnapshot)

                val document = knowledge.findById(DocumentId("doc-1"))
                assertNotNull(document)
                assertEquals("Legacy saved body", document?.normalizedText)
                assertNull(document?.structuredContentFormat)
                assertNull(document?.structuredContent)

                val version = knowledge.versions(DocumentId("doc-1")).single()
                assertEquals("parser-v2", version.parserVersion)
                assertNull(version.structuredContentFormat)
                assertNull(version.structuredContent)
                assertEquals("Historical Source", knowledge.provenance(DocumentId("doc-1")).single().sourceNameSnapshot)
            } finally {
                database.close()
            }
        } finally {
            Files.deleteIfExists(databaseFile)
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-wal"))
            Files.deleteIfExists(Path.of(databaseFile.toString() + "-shm"))
        }
    }

    private fun seedVersion6(connection: SQLiteConnection) {
        execute(
            connection,
            "INSERT INTO sources (id,name,type,url,enabled,pollIntervalSeconds,requiresUnmeteredNetwork,adapterType,configurationJson,createdAtEpochMillis,settingsRevision) " +
                "VALUES ('source-1','Historical Source','RSS','https://example.test/feed.xml',1,3600,0,'rss-atom','{}',10,0)",
        )
        execute(
            connection,
            "INSERT INTO inbox_items (id,canonicalUrl,title,publishedAtEpochMillis,normalizedText,contentHash,createdAtEpochMillis,updatedAtEpochMillis,parserVersion) " +
                "VALUES ('inbox-1','https://example.test/inbox','Legacy Inbox',NULL,'Legacy inbox body','inbox-hash',100,101,'default-content-extractor-v2')",
        )
        execute(
            connection,
            "INSERT INTO inbox_origins (inboxItemId,discoveredItemId,sourceId,discoveredUrl,resolvedUrl,canonicalUrl,discoveredAtEpochMillis,fetchedAtEpochMillis,sourceNameSnapshot,sourceUrlSnapshot,sourceTypeSnapshot) " +
                "VALUES ('inbox-1','discovery-1','source-1','https://example.test/inbox','https://example.test/inbox','https://example.test/inbox',90,95,'Historical Source','https://example.test/feed.xml','RSS')",
        )
        execute(
            connection,
            "INSERT INTO documents (id,canonicalUrl,title,author,publishedAtEpochMillis,language,normalizedText,contentHash,createdAtEpochMillis,updatedAtEpochMillis,disposition) " +
                "VALUES ('doc-1','https://example.test/saved','Legacy Saved',NULL,NULL,NULL,'Legacy saved body','doc-hash',110,111,'SAVED')",
        )
        execute(
            connection,
            "INSERT INTO document_versions (documentId,version,contentHash,normalizedText,fetchedAtEpochMillis,parserVersion) " +
                "VALUES ('doc-1',1,'doc-hash','Legacy saved body',109,'parser-v2')",
        )
        execute(
            connection,
            "INSERT INTO document_provenance (documentId,originKey,sourceId,discoveredUrl,resolvedUrl,discoveredAtEpochMillis,fetchedAtEpochMillis,sourceNameSnapshot,sourceUrlSnapshot,sourceTypeSnapshot) " +
                "VALUES ('doc-1','source-1|https://example.test/saved','source-1','https://example.test/saved','https://example.test/saved',105,109,'Historical Source','https://example.test/feed.xml','RSS')",
        )
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
