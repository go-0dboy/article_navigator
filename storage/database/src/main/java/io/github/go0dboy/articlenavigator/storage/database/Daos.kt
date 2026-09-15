package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE enabled = 1 AND (nextCheckAtEpochMillis IS NULL OR nextCheckAtEpochMillis <= :nowEpochMillis) ORDER BY nextCheckAtEpochMillis ASC, id ASC")
    suspend fun findDue(nowEpochMillis: Long): List<SourceEntity>

    @Upsert
    suspend fun upsertCursor(cursor: SourceCursorEntity)

    @Query("SELECT * FROM source_cursors WHERE sourceId = :sourceId LIMIT 1")
    suspend fun findCursor(sourceId: String): SourceCursorEntity?
}

@Dao
interface DiscoveryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: DiscoveredItemEntity)

    @Upsert
    suspend fun upsert(item: DiscoveredItemEntity)

    @Query("SELECT * FROM discovered_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DiscoveredItemEntity?

    @Query("SELECT * FROM discovered_items WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun findBySourceAndUrl(sourceId: String, url: String): DiscoveredItemEntity?

    @Query("SELECT COUNT(*) FROM discovered_items")
    suspend fun count(): Int
}

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE sourceId = :sourceId AND canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun findBySourceAndCanonicalUrl(sourceId: String, canonicalUrl: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: DocumentVersionEntity)

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version ASC")
    suspend fun versions(documentId: String): List<DocumentVersionEntity>

    @Upsert
    suspend fun upsertFingerprint(fingerprint: SeenFingerprintEntity)

    @Query("SELECT * FROM seen_fingerprints WHERE sourceId = :sourceId AND canonicalUrlHash = :canonicalUrlHash LIMIT 1")
    suspend fun findFingerprint(sourceId: String, canonicalUrlHash: String): SeenFingerprintEntity?

    @Transaction
    suspend fun persist(
        document: DocumentEntity,
        version: DocumentVersionEntity?,
        fingerprint: SeenFingerprintEntity?,
    ) {
        upsert(document)
        if (version != null) {
            insertVersion(version)
        }
        if (fingerprint != null) {
            upsertFingerprint(fingerprint)
        }
    }
}
