package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionLeaseDueGuardIntegrationTest {
    @Test
    fun staleDueSnapshotCannotClaimAfterNewerRunSchedulesSourceInFuture() = runTest {
        val now = Instant.parse("2026-09-15T10:00:00Z")
        val database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
            val collection = RoomCollectionRepository(database.collectionDao())
            val source = Source(
                id = SourceId("source"),
                name = "Source",
                type = SourceType.RSS,
                url = "https://example.test/feed.xml",
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = "test",
                createdAt = now.minusSeconds(3600),
                nextCheckAt = now,
            )
            sources.upsert(source)

            // Simulate two runs that both observed the source as due before either claimed it.
            assertEquals(listOf(source.id), sources.findDue(now).map { it.id })
            val firstLease = collection.tryClaim(
                sourceId = source.id,
                runToken = "run-a",
                now = now,
                leaseExpiresAt = now.plusSeconds(120),
            )!!
            assertEquals(
                CollectionCommitOutcome.APPLIED,
                collection.commitSuccess(
                    lease = firstLease,
                    items = emptyList(),
                    cursor = SourceCursor(source.id, etag = "etag-a", lastCheckedAt = now.plusSeconds(1)),
                    completedAt = now.plusSeconds(1),
                    nextCheckAt = now.plusSeconds(3600),
                    discoveredCount = 0,
                ),
            )

            // The second run still holds a stale in-memory due candidate. The atomic claim must
            // re-check nextCheckAt and refuse a duplicate collection/network request.
            val staleClaim = collection.tryClaim(
                sourceId = source.id,
                runToken = "run-b",
                now = now.plusSeconds(2),
                leaseExpiresAt = now.plusSeconds(122),
            )

            assertNull(staleClaim)
            assertEquals(now.plusSeconds(3600), sources.findById(source.id)?.nextCheckAt)
            assertEquals("etag-a", sources.loadCursor(source.id)?.etag)
        } finally {
            database.close()
        }
    }
}
