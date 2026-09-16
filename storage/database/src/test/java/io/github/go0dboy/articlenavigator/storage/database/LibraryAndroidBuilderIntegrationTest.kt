package io.github.go0dboy.articlenavigator.storage.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryAndroidBuilderIntegrationTest {
    private val now = Instant.parse("2026-09-16T11:00:00Z")

    @Test
    fun activeLibraryObserverDoesNotBlockExistingDocumentSaveThroughAndroidBuilder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "library-android-builder-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder<ArticleNavigatorDatabase>(context, name)
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val source = Source(
                id = SourceId("android-builder-source"),
                name = "Android builder source",
                type = SourceType.RSS,
                url = "https://example.test/android-builder.xml",
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = "rss-atom",
                createdAt = now.minusSeconds(3600),
                nextCheckAt = now,
            )
            val sources = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
            val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
            val inbox = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
            val knowledge = RoomKnowledgeRepository(db.documentDao())
            val library = RoomLibraryRepository(db.libraryReadDao())
            sources.upsert(source)

            val document = Document(
                id = DocumentId("android-builder-document"),
                canonicalUrl = "https://example.test/android-builder/article",
                title = "Before",
                normalizedText = "Before body",
                contentHash = "android-builder-v1",
                createdAt = now,
                updatedAt = now,
                disposition = ContentDisposition.SAVED,
            )
            knowledge.persist(
                document = document,
                version = DocumentVersion(
                    documentId = document.id,
                    version = 1,
                    contentHash = document.contentHash,
                    normalizedText = document.normalizedText,
                    fetchedAt = now,
                    parserVersion = "default-content-extractor-v2",
                ),
                provenance = DocumentProvenance(
                    documentId = document.id,
                    sourceId = source.id,
                    discoveredUrl = "${document.canonicalUrl}?original=1",
                    resolvedUrl = document.canonicalUrl,
                    discoveredAt = now.minusSeconds(30),
                    fetchedAt = now.minusSeconds(20),
                    sourceNameSnapshot = source.name,
                    sourceUrlSnapshot = source.url,
                    sourceTypeSnapshot = source.type.name,
                ),
                fingerprint = SeenFingerprint(
                    canonicalUrlHash = "android-builder-old-fingerprint",
                    contentHash = document.contentHash,
                    sourceId = source.id,
                    seenAt = now,
                    disposition = ContentDisposition.SAVED,
                ),
            )

            val discovery = DiscoveredItem(
                id = DiscoveredItemId("android-builder-discovery"),
                sourceId = source.id,
                url = document.canonicalUrl,
                canonicalUrl = document.canonicalUrl,
                resolvedUrl = document.canonicalUrl,
                title = "After",
                discoveredAt = now.plusSeconds(10),
                status = DiscoveryStatus.FETCHED,
            )
            ingestion.upsertDiscovered(discovery)
            val pending = InboxItem(
                id = InboxItemId("android-builder-inbox"),
                canonicalUrl = document.canonicalUrl,
                title = "After",
                normalizedText = "After body",
                contentHash = "android-builder-v2",
                createdAt = now.plusSeconds(20),
                updatedAt = now.plusSeconds(20),
                parserVersion = "default-content-extractor-v2",
            )
            inbox.put(
                pending,
                InboxOrigin(
                    inboxItemId = pending.id,
                    discoveredItemId = discovery.id,
                    sourceId = source.id,
                    discoveredUrl = discovery.url,
                    resolvedUrl = discovery.resolvedUrl,
                    canonicalUrl = checkNotNull(discovery.canonicalUrl),
                    discoveredAt = discovery.discoveredAt,
                    fetchedAt = now.plusSeconds(15),
                    sourceNameSnapshot = source.name,
                    sourceUrlSnapshot = source.url,
                    sourceTypeSnapshot = source.type.name,
                ),
            )

            val firstEmission = CompletableDeferred<Unit>()
            val observer = launch {
                library.observeSavedCount().collect { firstEmission.complete(Unit) }
            }
            withTimeout(5_000) { firstEmission.await() }

            assertEquals(document.id, inbox.saveCurrent(pending.id, now.plusSeconds(30), "ignored"))
            observer.cancelAndJoin()
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
