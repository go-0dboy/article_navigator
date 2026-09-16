package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
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
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingLeaseStaleFinalizeTest {
    @Test
    fun expiredOwnerLateSuccessCannotOverwriteReplacementOwner() = runTest {
        val db = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val now = Instant.parse("2026-09-16T09:00:00Z")
            val source = Source(
                id = SourceId("source"),
                name = "Source",
                type = SourceType.RSS,
                url = "https://example.test/feed.xml",
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = "test",
                createdAt = now.minusSeconds(3600),
            )
            RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao()).upsert(source)
            val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
            val discovered = DiscoveredItem(
                id = DiscoveredItemId("item"),
                sourceId = source.id,
                url = "https://example.test/article",
                canonicalUrl = "https://example.test/article",
                discoveredAt = now.minusSeconds(60),
            )
            ingestion.upsertDiscovered(discovered)
            val old = checkNotNull(ingestion.tryClaimNext("old", now, now.plusSeconds(1), true))
            val replacementAt = now.plusSeconds(2)
            val current = checkNotNull(
                ingestion.tryClaimNext("current", replacementAt, replacementAt.plusSeconds(60), true),
            )
            val inboxItem = InboxItem(
                id = InboxItemId("inbox"),
                canonicalUrl = discovered.url,
                title = "Article",
                normalizedText = "body",
                contentHash = "hash",
                createdAt = replacementAt,
                updatedAt = replacementAt,
            )
            val origin = InboxOrigin(
                inboxItemId = inboxItem.id,
                discoveredItemId = discovered.id,
                sourceId = source.id,
                discoveredUrl = discovered.url,
                resolvedUrl = discovered.url,
                canonicalUrl = discovered.url,
                discoveredAt = discovered.discoveredAt,
                fetchedAt = replacementAt,
                sourceNameSnapshot = source.name,
                sourceUrlSnapshot = source.url,
                sourceTypeSnapshot = source.type.name,
            )

            assertEquals(
                IngestionFinalizeOutcome.STALE,
                ingestion.finalizeSuccess(old, inboxItem, origin, "url-hash", replacementAt),
            )
            assertEquals("current", db.articleProcessingDao().item(discovered.id.value)?.processingLeaseToken)
            assertNull(RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao()).findById(inboxItem.id))
            assertEquals(DiscoveryStatus.DISCOVERED, ingestion.findDiscoveredById(discovered.id)?.status)

            assertEquals(
                IngestionFinalizeOutcome.ADDED_TO_INBOX,
                ingestion.finalizeSuccess(current, inboxItem, origin, "url-hash", replacementAt.plusSeconds(1)),
            )
            assertEquals(DiscoveryStatus.PROCESSED, ingestion.findDiscoveredById(discovered.id)?.status)
            assertNull(db.articleProcessingDao().item(discovered.id.value)?.processingLeaseToken)
        } finally {
            db.close()
        }
    }
}
