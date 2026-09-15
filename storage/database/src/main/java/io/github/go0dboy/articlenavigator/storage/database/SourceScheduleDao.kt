package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Query

/** Scheduler-only addressed updates. These never mutate user source settings. */
@Dao
interface SourceScheduleDao {
    @Query("UPDATE sources SET nextCheckAtEpochMillis = :atEpochMillis WHERE id = :sourceId")
    suspend fun markDue(sourceId: String, atEpochMillis: Long): Int
}
