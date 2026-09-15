package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionLeaseReleaseIntegrationTest {
    @Test
    fun staleOwnerReleaseCannotClearReplacementLease() = runTest {
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

            val stale = collection.tryClaim(
                sourceId = source.id,
                runToken = "run-a",
                now = now,
                leaseExpiresAt = now.plusSeconds(10),
            )!!
            val replacement = collection.tryClaim(
                sourceId = source.id,
                runToken = "run-b",
                now = now.plusSeconds(11),
                leaseExpiresAt = now.plusSeconds(120),
            )!!
            assertNotNull(replacement)

            collection.release(stale)

            // If stale release had cleared run-b, this third claim would succeed.
            val third = collection.tryClaim(
                sourceId = source.id,
                runToken = "run-c",
                now = now.plusSeconds(12),
                leaseExpiresAt = now.plusSeconds(130),
            )
            assertNull(third)
        } finally {
            database.close()
        }
    }
}
