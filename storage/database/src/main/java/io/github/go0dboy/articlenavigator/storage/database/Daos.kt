package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

data class CollectionClaimSnapshot(
    val source: SourceEntity,
    val cursor: SourceCursorEntity?,
    val state: SourceCollectionStateEntity?,
)

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(source: SourceEntity): Long

    @Query(
        """
        UPDATE sources SET
            name = :name,
            type = :type,
            url = :url,
            enabled = :enabled,
            pollIntervalSeconds = :pollIntervalSeconds,
            requiresUnmeteredNetwork = :requiresUnmeteredNetwork,
            adapterType = :adapterType,
            configurationJson = :configurationJson,
            settingsRevision = settingsRevision + 1
        WHERE id = :id
        """,
    )
    suspend fun updateUserSettings(
        id: String,
        name: String,
        type: String,
        url: String,
        enabled: Boolean,
        pollIntervalSeconds: Long,
        requiresUnmeteredNetwork: Boolean,
        adapterType: String,
        configurationJson: String,
    ): Int

    @Transaction
    suspend fun saveUserSource(source: SourceEntity) {
        if (insertIfAbsent(source) == -1L) {
            updateUserSettings(
                id = source.id,
                name = source.name,
                type = source.type,
                url = source.url,
                enabled = source.enabled,
                pollIntervalSeconds = source.pollIntervalSeconds,
                requiresUnmeteredNetwork = source.requiresUnmeteredNetwork,
                adapterType = source.adapterType,
                configurationJson = source.configurationJson,
            )
        }
    }

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SourceEntity?

    @Query(
        """
        SELECT * FROM sources
        WHERE enabled = 1
          AND (nextCheckAtEpochMillis IS NULL OR nextCheckAtEpochMillis <= :nowEpochMillis)
          AND (leaseToken IS NULL OR leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis <= :nowEpochMillis)
        ORDER BY nextCheckAtEpochMillis, id
        """,
    )
    suspend fun findDue(nowEpochMillis: Long): List<SourceEntity>

    @Query(
        """
        SELECT * FROM sources
        WHERE enabled = 1
          AND (nextCheckAtEpochMillis IS NULL OR nextCheckAtEpochMillis <= :nowEpochMillis)
          AND (leaseToken IS NULL OR leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis <= :nowEpochMillis)
        ORDER BY nextCheckAtEpochMillis, id
        LIMIT :limit
        """,
    )
    suspend fun findDue(nowEpochMillis: Long, limit: Int): List<SourceEntity>

    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    suspend fun listAll(): List<SourceEntity>

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

/** Transaction boundary for source collection. Network calls must never be made from this DAO. */
@Dao
interface CollectionDao {
    @Query(
        """
        UPDATE sources
        SET leaseToken = :runToken,
            leaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis
        WHERE id = :sourceId
          AND enabled = 1
          AND (leaseToken IS NULL OR leaseExpiresAtEpochMillis IS NULL OR leaseExpiresAtEpochMillis <= :nowEpochMillis)
        """,
    )
    suspend fun claim(
        sourceId: String,
        runToken: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): Int

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun source(sourceId: String): SourceEntity?

    @Query("SELECT * FROM source_cursors WHERE sourceId = :sourceId LIMIT 1")
    suspend fun cursor(sourceId: String): SourceCursorEntity?

    @Query("SELECT * FROM source_collection_states WHERE sourceId = :sourceId LIMIT 1")
    suspend fun state(sourceId: String): SourceCollectionStateEntity?

    @Transaction
    suspend fun tryClaim(
        sourceId: String,
        runToken: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): CollectionClaimSnapshot? {
        if (claim(sourceId, runToken, nowEpochMillis, leaseExpiresAtEpochMillis) != 1) return null
        val claimedSource = checkNotNull(source(sourceId)) { "Claimed source disappeared: $sourceId" }
        return CollectionClaimSnapshot(
            source = claimedSource,
            cursor = cursor(sourceId),
            state = state(sourceId),
        )
    }

    @Query(
        """
        SELECT COUNT(*) FROM sources
        WHERE id = :sourceId
          AND leaseToken = :runToken
          AND settingsRevision = :settingsRevision
          AND leaseExpiresAtEpochMillis IS NOT NULL
          AND leaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun ownsLease(
        sourceId: String,
        runToken: String,
        settingsRevision: Long,
        atEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscoveryIfAbsent(item: DiscoveredItemEntity): Long

    /** Only remote/source metadata is refreshed; local processing state is intentionally untouched. */
    @Query(
        """
        UPDATE discovered_items SET
            canonicalUrl = COALESCE(:canonicalUrl, canonicalUrl),
            resolvedUrl = COALESCE(:resolvedUrl, resolvedUrl),
            title = COALESCE(:title, title),
            publishedAtEpochMillis = COALESCE(:publishedAtEpochMillis, publishedAtEpochMillis),
            lastSeenAtEpochMillis = :lastSeenAtEpochMillis
        WHERE id = :id
        """,
    )
    suspend fun updateDiscoveryMetadata(
        id: String,
        canonicalUrl: String?,
        resolvedUrl: String?,
        title: String?,
        publishedAtEpochMillis: Long?,
        lastSeenAtEpochMillis: Long,
    ): Int

    @Upsert
    suspend fun upsertCursor(cursor: SourceCursorEntity)

    @Upsert
    suspend fun upsertState(state: SourceCollectionStateEntity)

    @Query(
        """
        UPDATE sources SET
            lastSuccessfulCheckAtEpochMillis = :completedAtEpochMillis,
            nextCheckAtEpochMillis = :nextCheckAtEpochMillis,
            leaseToken = NULL,
            leaseExpiresAtEpochMillis = NULL
        WHERE id = :sourceId
          AND leaseToken = :runToken
          AND settingsRevision = :settingsRevision
          AND leaseExpiresAtEpochMillis IS NOT NULL
          AND leaseExpiresAtEpochMillis > :completedAtEpochMillis
        """,
    )
    suspend fun finishSuccess(
        sourceId: String,
        runToken: String,
        settingsRevision: Long,
        completedAtEpochMillis: Long,
        nextCheckAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE sources SET
            nextCheckAtEpochMillis = :nextCheckAtEpochMillis,
            leaseToken = NULL,
            leaseExpiresAtEpochMillis = NULL
        WHERE id = :sourceId
          AND leaseToken = :runToken
          AND settingsRevision = :settingsRevision
          AND leaseExpiresAtEpochMillis IS NOT NULL
          AND leaseExpiresAtEpochMillis > :completedAtEpochMillis
        """,
    )
    suspend fun finishFailure(
        sourceId: String,
        runToken: String,
        settingsRevision: Long,
        completedAtEpochMillis: Long,
        nextCheckAtEpochMillis: Long,
    ): Int

    @Query("UPDATE sources SET leaseToken = NULL, leaseExpiresAtEpochMillis = NULL WHERE id = :sourceId AND leaseToken = :runToken")
    suspend fun release(sourceId: String, runToken: String): Int

    @Transaction
    suspend fun commitSuccess(
        sourceId: String,
        runToken: String,
        settingsRevision: Long,
        items: List<DiscoveredItemEntity>,
        nextCursor: SourceCursorEntity,
        state: SourceCollectionStateEntity,
        completedAtEpochMillis: Long,
        nextCheckAtEpochMillis: Long,
    ): Boolean {
        if (ownsLease(sourceId, runToken, settingsRevision, completedAtEpochMillis) != 1) return false
        for (item in items) {
            insertDiscoveryIfAbsent(item)
            updateDiscoveryMetadata(
                id = item.id,
                canonicalUrl = item.canonicalUrl,
                resolvedUrl = item.resolvedUrl,
                title = item.title,
                publishedAtEpochMillis = item.publishedAtEpochMillis,
                lastSeenAtEpochMillis = item.lastSeenAtEpochMillis,
            )
        }
        upsertCursor(nextCursor)
        upsertState(state)
        check(
            finishSuccess(
                sourceId,
                runToken,
                settingsRevision,
                completedAtEpochMillis,
                nextCheckAtEpochMillis,
            ) == 1,
        ) { "Collection lease changed during success commit" }
        return true
    }

    @Transaction
    suspend fun commitFailure(
        sourceId: String,
        runToken: String,
        settingsRevision: Long,
        state: SourceCollectionStateEntity,
        completedAtEpochMillis: Long,
        nextCheckAtEpochMillis: Long,
    ): Boolean {
        if (ownsLease(sourceId, runToken, settingsRevision, completedAtEpochMillis) != 1) return false
        upsertState(state)
        check(
            finishFailure(
                sourceId,
                runToken,
                settingsRevision,
                completedAtEpochMillis,
                nextCheckAtEpochMillis,
            ) == 1,
        ) { "Collection lease changed during failure commit" }
        return true
    }
}

@Dao
interface IngestionDao {
    /** Creation/import path only. Processing transitions use targeted updates below. */
    @Upsert
    suspend fun upsertDiscovered(item: DiscoveredItemEntity)

    @Query("SELECT * FROM discovered_items WHERE id = :id LIMIT 1")
    suspend fun findDiscoveredById(id: String): DiscoveredItemEntity?

    @Query("SELECT * FROM discovered_items WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun findDiscovered(sourceId: String, url: String): DiscoveredItemEntity?

    @Query("SELECT * FROM discovered_items WHERE status IN ('DISCOVERED', 'FAILED') AND (nextProcessingAtEpochMillis IS NULL OR nextProcessingAtEpochMillis <= :nowEpochMillis) ORDER BY discoveredAtEpochMillis LIMIT :limit")
    suspend fun findReadyForProcessing(nowEpochMillis: Long, limit: Int): List<DiscoveredItemEntity>

    @Query("SELECT COUNT(*) FROM discovered_items")
    suspend fun countDiscovered(): Int

    @Query("SELECT * FROM discovered_items ORDER BY discoveredAtEpochMillis DESC LIMIT :limit")
    suspend fun latestDiscovered(limit: Int): List<DiscoveredItemEntity>

    @Query(
        """
        UPDATE discovered_items SET
            canonicalUrl = COALESCE(:canonicalUrl, canonicalUrl),
            status = 'PROCESSED',
            nextProcessingAtEpochMillis = NULL,
            lastProcessingError = NULL
        WHERE id = :id
        """,
    )
    suspend fun markProcessed(id: String, canonicalUrl: String?): Int

    @Query(
        """
        UPDATE discovered_items SET
            canonicalUrl = COALESCE(:canonicalUrl, canonicalUrl),
            resolvedUrl = COALESCE(:resolvedUrl, resolvedUrl),
            contentHash = :contentHash,
            status = 'FETCHED',
            nextProcessingAtEpochMillis = NULL,
            lastProcessingError = NULL
        WHERE id = :id
        """,
    )
    suspend fun markFetched(
        id: String,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
    ): Int

    @Query(
        """
        UPDATE discovered_items SET
            status = 'FAILED',
            processingAttempts = :processingAttempts,
            nextProcessingAtEpochMillis = :nextProcessingAtEpochMillis,
            lastProcessingError = :lastProcessingError
        WHERE id = :id
        """,
    )
    suspend fun markFailed(
        id: String,
        processingAttempts: Int,
        nextProcessingAtEpochMillis: Long,
        lastProcessingError: String,
    ): Int

    @Query(
        """
        UPDATE discovered_items SET
            canonicalUrl = COALESCE(:canonicalUrl, canonicalUrl),
            status = 'SKIPPED',
            nextProcessingAtEpochMillis = NULL,
            lastProcessingError = :lastProcessingError
        WHERE id = :id
        """,
    )
    suspend fun markSkipped(id: String, canonicalUrl: String?, lastProcessingError: String): Int

    @Upsert
    suspend fun upsertRawContent(content: RawContentEntity)

    @Query("SELECT * FROM raw_contents WHERE discoveredItemId = :id LIMIT 1")
    suspend fun findRawContent(id: String): RawContentEntity?

    @Query("DELETE FROM raw_contents WHERE discoveredItemId = :id")
    suspend fun deleteRawContent(id: String)
}

@Dao
interface InboxDao {
    @Upsert
    suspend fun upsertItem(item: InboxItemEntity)

    @Upsert
    suspend fun upsertOrigin(origin: InboxOriginEntity)

    @Upsert
    suspend fun upsertFingerprints(fingerprints: List<SeenFingerprintEntity>)

    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    @Upsert
    suspend fun upsertVersion(version: DocumentVersionEntity)

    @Upsert
    suspend fun upsertProvenances(provenances: List<DocumentProvenanceEntity>)

    @Query("SELECT * FROM inbox_items ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun listPending(limit: Int): List<InboxItemEntity>

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items WHERE contentHash = :contentHash ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun findByContentHash(contentHash: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_origins WHERE inboxItemId = :inboxItemId ORDER BY discoveredAtEpochMillis")
    suspend fun origins(inboxItemId: String): List<InboxOriginEntity>

    @Query("UPDATE discovered_items SET status = 'PROCESSED', nextProcessingAtEpochMillis = NULL, lastProcessingError = NULL WHERE id IN (:ids)")
    suspend fun markDiscoveriesProcessed(ids: List<String>)

    @Query("DELETE FROM raw_contents WHERE discoveredItemId IN (:ids)")
    suspend fun deleteRawContents(ids: List<String>)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    @Transaction
    suspend fun put(item: InboxItemEntity, origin: InboxOriginEntity) {
        upsertItem(item)
        upsertOrigin(origin)
        markDiscoveriesProcessed(listOf(origin.discoveredItemId))
        deleteRawContents(listOf(origin.discoveredItemId))
    }

    @Transaction
    suspend fun attachOrigin(itemId: String, origin: InboxOriginEntity) {
        require(itemId == origin.inboxItemId)
        upsertOrigin(origin)
        markDiscoveriesProcessed(listOf(origin.discoveredItemId))
        deleteRawContents(listOf(origin.discoveredItemId))
    }

    @Transaction
    suspend fun discard(itemId: String, fingerprints: List<SeenFingerprintEntity>) {
        val discoveryIds = origins(itemId).map { it.discoveredItemId }
        if (fingerprints.isNotEmpty()) upsertFingerprints(fingerprints)
        if (discoveryIds.isNotEmpty()) {
            markDiscoveriesProcessed(discoveryIds)
            deleteRawContents(discoveryIds)
        }
        deleteItem(itemId)
    }

    @Transaction
    suspend fun save(
        itemId: String,
        document: DocumentEntity,
        version: DocumentVersionEntity,
        provenances: List<DocumentProvenanceEntity>,
        fingerprints: List<SeenFingerprintEntity>,
    ) {
        val discoveryIds = origins(itemId).map { it.discoveredItemId }
        upsertDocument(document)
        upsertVersion(version)
        if (provenances.isNotEmpty()) upsertProvenances(provenances)
        if (fingerprints.isNotEmpty()) upsertFingerprints(fingerprints)
        if (discoveryIds.isNotEmpty()) {
            markDiscoveriesProcessed(discoveryIds)
            deleteRawContents(discoveryIds)
        }
        deleteItem(itemId)
    }
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

    @Query("SELECT * FROM documents WHERE contentHash = :contentHash ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun findByContentHash(contentHash: String): DocumentEntity?

    @Query("SELECT * FROM document_versions WHERE documentId = :documentId ORDER BY version")
    suspend fun versions(documentId: String): List<DocumentVersionEntity>

    @Query("SELECT * FROM document_provenance WHERE documentId = :documentId ORDER BY discoveredAtEpochMillis")
    suspend fun provenance(documentId: String): List<DocumentProvenanceEntity>

    @Query("SELECT * FROM seen_fingerprints WHERE canonicalUrlHash = :canonicalUrlHash AND sourceId = :sourceId LIMIT 1")
    suspend fun findFingerprint(canonicalUrlHash: String, sourceId: String): SeenFingerprintEntity?

    @Query("UPDATE documents SET disposition = :disposition, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateDisposition(id: String, disposition: String, updatedAtEpochMillis: Long)

    @Query("UPDATE discovered_items SET status = 'PROCESSED', nextProcessingAtEpochMillis = NULL, lastProcessingError = NULL WHERE id = :id")
    suspend fun markDiscoveryProcessed(id: String)

    @Query("DELETE FROM raw_contents WHERE discoveredItemId = :id")
    suspend fun deleteRawContent(id: String)

    @Transaction
    suspend fun recordDiscovery(
        provenance: DocumentProvenanceEntity,
        fingerprint: SeenFingerprintEntity,
        discoveredItemId: String,
    ) {
        upsertProvenance(provenance)
        upsertFingerprint(fingerprint)
        markDiscoveryProcessed(discoveredItemId)
        deleteRawContent(discoveredItemId)
    }
}
