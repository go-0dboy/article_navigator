package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxProvenancePersistenceTest {
    @Test
    fun sourceEditsAfterInboxAttachmentDoNotRewriteOriginSnapshot() = runTest {
        val database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
            val ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
            val inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
            val now = Instant.parse("2026-09-15T18:00:00Z")
            val source = Source(
                id = SourceId("source"),
                name = "Original feed",
                type = SourceType.RSS,
                url = "https://example.test/original.xml",
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = "rss-atom",
                createdAt = now.minusSeconds(3600),
            )
            sources.upsert(source)
            val discovery = DiscoveredItem(
                id = DiscoveredItemId("discovery"),
                sourceId = source.id,
                url = "https://example.test/article",
                canonicalUrl = "https://example.test/article",
                discoveredAt = now.minusSeconds(60),
            )
            ingestion.upsertDiscovered(discovery)
            val item = InboxItem(
                id = InboxItemId("inbox"),
                canonicalUrl = "https://example.test/article",
                title = "Article",
                normalizedText = "Body",
                contentHash = "hash",
                createdAt = now,
                updatedAt = now,
            )
            val origin = InboxOrigin(
                inboxItemId = item.id,
                discoveredItemId = discovery.id,
                sourceId = source.id,
                discoveredUrl = discovery.url,
                resolvedUrl = discovery.url,
                canonicalUrl = item.canonicalUrl,
                discoveredAt = discovery.discoveredAt,
                fetchedAt = now,
                sourceNameSnapshot = source.name,
                sourceUrlSnapshot = source.url,
                sourceTypeSnapshot = source.type.name,
            )
            inbox.put(item, origin)

            sources.upsert(
                source.copy(
                    name = "Renamed feed",
                    url = "https://example.test/renamed.xml",
                    type = SourceType.ATOM,
                ),
            )

            val persisted = inbox.origins(item.id).single()
            assertEquals("Original feed", persisted.sourceNameSnapshot)
            assertEquals("https://example.test/original.xml", persisted.sourceUrlSnapshot)
            assertEquals(SourceType.RSS.name, persisted.sourceTypeSnapshot)
        } finally {
            database.close()
        }
    }
}
