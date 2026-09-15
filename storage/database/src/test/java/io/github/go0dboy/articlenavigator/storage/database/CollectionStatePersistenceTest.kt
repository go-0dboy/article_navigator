package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionStatePersistenceTest {
    @Test
    fun stateRoundTripsThroughRealSqlite() = runTest {
        val db = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            val sourceRepository = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao())
            val stateRepository = RoomCollectionStateRepository(db.collectionStateDao())
            val sourceId = SourceId("source")
            sourceRepository.upsert(
                Source(
                    id = sourceId,
                    name = "Source",
                    type = SourceType.RSS,
                    url = "https://example.test/feed.xml",
                    enabled = true,
                    pollPolicy = PollPolicy(Duration.ofHours(1)),
                    adapterType = "rss-atom",
                    createdAt = Instant.EPOCH,
                ),
            )
            val expected = SourceCollectionState(
                sourceId = sourceId,
                consecutiveFailures = 2,
                lastAttemptAt = Instant.parse("2026-09-15T10:00:00Z"),
                lastErrorType = "java.io.IOException",
                lastErrorMessage = "timeout",
                lastDiscoveredCount = 7,
            )

            stateRepository.save(expected)

            assertEquals(expected, stateRepository.load(sourceId))
        } finally {
            db.close()
        }
    }
}
