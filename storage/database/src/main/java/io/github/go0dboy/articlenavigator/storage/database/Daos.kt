package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE enabled = 1 AND (nextCheckAtEpochMillis IS NULL OR nextCheckAtEpochMillis <= :nowEpochMillis) ORDER BY nextCheckAtEpochMillis")
    suspend fun findDue(nowEpochMillis: Long): List<SourceEntity>

    @Upsert
    suspend fun upsertCursor(cursor: SourceCursorEntity)

    @Query("SELECT * FROM source_cursors WHERE sourceId = :sourceId LIMIT 1")
    suspend fun findCursor(sourceId: String): SourceCursorEntity?
}

@Dao
interface CollectionStateDao {
    @Upsert
    suspend fun upsert(state: SourceCollectionStateEntity)

    @Query("SELECT * FROM source_collection_states WHERE sourceId = :sourceId LIMIT 1")
    suspend fun findBySourceId(sourceId: String): SourceCollectionStateEntity?
}

@Dao
interface IngestionDao {
    @Upsert
    suspend fun upsertDiscovered(item: DiscoveredItemEntity)

    @Query("SELECT * FROM discovered_items WHERE id = :id LIMIT 1")
    suspend fun findDiscoveredById(id: String): DiscoveredItemEntity?

    @Query("SELECT * FROM discovered_items WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun findDiscovered(sourceId: String, url: String): DiscoveredItemEntity?

    @Query("SELECT COUNT(*) FROM discovered_items")
    suspend fun countDiscovered(): Int

    @Query("SELECT * FROM discovered_items ORDER BY discoveredAtEpochMillis DESC LIMIT :limit")
    suspend fun latestDiscovered(limit: Int): List<DiscoveredItemEntity>

    @Upsert
    suspend fun upsertRawContent(content: RawContentEntity)

    @Query("SELECT * FROM raw_contents WHERE discoveredItemId = :id LIMIT 1")
    suspend fun findRawContent(id: String): RawContentEntity?
}

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsert(document: DocumentEntity)

    @Upsert
    suspend fun upsertVersion(version: DocumentVersionEntity)

    @Upsert
    suspend fun upsertProvenance(provenance: DocumentProvenanceEntity)

    @Upsert
    suspend fun upsertFingerprint(fingerprint: SeenFingerprintEntity)

    @Transaction
    suspend fun persist(
        document: DocumentEntity,
        version: DocumentVersionEntity,
        provenance: DocumentProvenanceEntity,
        fingerprint: SeenFingerprintEntity,
    ) {
        upsert(document)
        upsertVersion(version)
        upsertProvenance(provenance)
        upsertFingerprint(fingerprint)
    }

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun findByCanonicalUrl(canonicalUrl: String): DocumentEntity?

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version")
    suspend fun versions(documentId: String): List<DocumentVersionEntity>

    @Query("SELECT * FROM document_provenance WHERE documentId = :documentId ORDER BY discoveredAtEpochMillis")
    suspend fun provenance(documentId: String): List<DocumentProvenanceEntity>

    @Query("SELECT * FROM seen_fingerprints WHERE canonicalUrlHash = :canonicalUrlHash AND sourceId = :sourceId LIMIT 1")
    suspend fun findFingerprint(canonicalUrlHash: String, sourceId: String): SeenFingerprintEntity?

    @Query("UPDATE documents SET disposition = :disposition, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateDisposition(id: String, disposition: String, updatedAtEpochMillis: Long)
}
