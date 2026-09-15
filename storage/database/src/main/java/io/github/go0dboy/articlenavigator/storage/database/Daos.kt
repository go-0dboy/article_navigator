package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE enabled = 1 AND (nextCheckAtEpochMillis IS NULL OR nextCheckAtEpochMillis <= :nowEpochMillis)")
    suspend fun findDue(nowEpochMillis: Long): List<SourceEntity>
}

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DocumentEntity?
}
