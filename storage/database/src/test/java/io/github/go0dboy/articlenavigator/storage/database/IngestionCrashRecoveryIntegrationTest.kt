package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class IngestionCrashRecoveryIntegrationTest {
    @Test
    fun fetchedStateIsRecoverableAfterInterruptedIngestion() = runTest {
        val database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
            val ingestion = RoomIngestionRepository(database.ingestionDao())
            val now = Instant.parse("2026-09-15T18:00:00Z")
            val source = Source(
                id = SourceId("source"),
                name = "Source",
                type = SourceType.RSS,
                url = "https://example.test/feed.xml",
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = "rss-atom",
                createdAt = now.minusSeconds(3_600),
            )
            sources.upsert(source)

            val interrupted = DiscoveredItem(
                id = DiscoveredItemId("fetched-interrupted"),
                sourceId = source.id,
                url = "https://example.test/article",
                canonicalUrl = "https://example.test/article",
                resolvedUrl = "https://example.test/article-final",
                discoveredAt = now.minusSeconds(60),
                lastSeenAt = now.minusSeconds(60),
                contentHash = "content-hash",
                status = DiscoveryStatus.FETCHED,
                nextProcessingAt = null,
            )
            val terminal = interrupted.copy(
                id = DiscoveredItemId("processed-terminal"),
                url = "https://example.test/processed",
                canonicalUrl = "https://example.test/processed",
                status = DiscoveryStatus.PROCESSED,
            )
            ingestion.upsertDiscovered(interrupted)
            ingestion.upsertDiscovered(terminal)

            val ready = ingestion.findReadyForProcessing(now, 10)

            assertEquals(listOf(interrupted.id), ready.map { it.id })
            assertEquals(DiscoveryStatus.FETCHED, ready.single().status)
            assertEquals("content-hash", ready.single().contentHash)
            assertEquals("https://example.test/article-final", ready.single().resolvedUrl)
        } finally {
            database.close()
        }
    }
}
