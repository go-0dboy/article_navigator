package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Lightweight row used by the library list; normalizedText is intentionally truncated in SQL. */
data class LibraryListRow(
    val id: String,
    val title: String,
    val savedAtEpochMillis: Long,
    val snippet: String,
    val sourceCount: Int,
    val primarySourceName: String?,
)

@Dao
interface LibraryReadDao {
    @Query("SELECT COUNT(*) FROM documents WHERE disposition = 'SAVED'")
    fun observeSavedCount(): Flow<Int>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM documents WHERE disposition = 'SAVED') * 1000003
            + (SELECT COUNT(*) FROM document_provenance)
        """,
    )
    fun observeRevision(): Flow<Long>

    @Query(
        """
        SELECT
            d.id AS id,
            d.title AS title,
            d.createdAtEpochMillis AS savedAtEpochMillis,
            substr(d.normalizedText, 1, 240) AS snippet,
            (SELECT COUNT(*) FROM document_provenance p WHERE p.documentId = d.id) AS sourceCount,
            (
                SELECT p.sourceNameSnapshot
                FROM document_provenance p
                WHERE p.documentId = d.id
                ORDER BY p.discoveredAtEpochMillis ASC, p.originKey ASC
                LIMIT 1
            ) AS primarySourceName
        FROM documents d
        WHERE d.disposition = 'SAVED'
          AND (
              :afterSavedAtEpochMillis IS NULL
              OR d.createdAtEpochMillis < :afterSavedAtEpochMillis
              OR (d.createdAtEpochMillis = :afterSavedAtEpochMillis AND d.id < :afterDocumentId)
          )
        ORDER BY d.createdAtEpochMillis DESC, d.id DESC
        LIMIT :limit
        """,
    )
    suspend fun loadPage(
        afterSavedAtEpochMillis: Long?,
        afterDocumentId: String?,
        limit: Int,
    ): List<LibraryListRow>

    @Query("SELECT * FROM documents WHERE id = :id AND disposition = 'SAVED' LIMIT 1")
    fun observeSavedDocument(id: String): Flow<DocumentEntity?>

    @Query(
        """
        SELECT * FROM document_provenance
        WHERE documentId = :documentId
        ORDER BY discoveredAtEpochMillis ASC, originKey ASC
        """,
    )
    fun observeProvenance(documentId: String): Flow<List<DocumentProvenanceEntity>>
}

@Dao
interface InboxPagingDao {
    @Query("SELECT COUNT(*) FROM inbox_items")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM inbox_items")
    fun observeRevision(): Flow<Long>

    @Query(
        """
        SELECT * FROM inbox_items
        WHERE (
            :afterCreatedAtEpochMillis IS NULL
            OR createdAtEpochMillis < :afterCreatedAtEpochMillis
            OR (createdAtEpochMillis = :afterCreatedAtEpochMillis AND id < :afterItemId)
        )
        ORDER BY createdAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun loadPage(
        afterCreatedAtEpochMillis: Long?,
        afterItemId: String?,
        limit: Int,
    ): List<InboxItemEntity>
}
