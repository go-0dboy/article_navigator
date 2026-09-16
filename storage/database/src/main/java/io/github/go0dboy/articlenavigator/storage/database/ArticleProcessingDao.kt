package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome

@Dao
interface ArticleProcessingDao {
    @Query(
        """
        SELECT d.id
        FROM discovered_items d
        JOIN sources s ON s.id = d.sourceId
        WHERE d.status IN ('DISCOVERED', 'FAILED', 'FETCHED')
          AND (d.nextProcessingAtEpochMillis IS NULL OR d.nextProcessingAtEpochMillis <= :nowEpochMillis)
          AND (d.processingLeaseToken IS NULL OR d.processingLeaseExpiresAtEpochMillis IS NULL OR d.processingLeaseExpiresAtEpochMillis <= :nowEpochMillis)
          AND s.enabled = 1
          AND (:isUnmeteredNetwork = 1 OR s.requiresUnmeteredNetwork = 0)
        ORDER BY d.discoveredAtEpochMillis, d.id
        LIMIT 1
        """,
    )
    suspend fun nextEligibleId(nowEpochMillis: Long, isUnmeteredNetwork: Boolean): String?

    @Query(
        """
        UPDATE discovered_items SET
            processingLeaseToken = :runToken,
            processingLeaseExpiresAtEpochMillis = :leaseExpiresAtEpochMillis
        WHERE id = :id
          AND status IN ('DISCOVERED', 'FAILED', 'FETCHED')
          AND (nextProcessingAtEpochMillis IS NULL OR nextProcessingAtEpochMillis <= :nowEpochMillis)
          AND (processingLeaseToken IS NULL OR processingLeaseExpiresAtEpochMillis IS NULL OR processingLeaseExpiresAtEpochMillis <= :nowEpochMillis)
        """,
    )
    suspend fun claim(id: String, runToken: String, nowEpochMillis: Long, leaseExpiresAtEpochMillis: Long): Int

    @Query("SELECT * FROM discovered_items WHERE id = :id LIMIT 1")
    suspend fun item(id: String): DiscoveredItemEntity?

    @Transaction
    suspend fun tryClaimNext(
        runToken: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
        isUnmeteredNetwork: Boolean,
    ): DiscoveredItemEntity? {
        val id = nextEligibleId(nowEpochMillis, isUnmeteredNetwork) ?: return null
        if (claim(id, runToken, nowEpochMillis, leaseExpiresAtEpochMillis) != 1) return null
        return checkNotNull(item(id)) { "Claimed discovery disappeared: $id" }
    }

    @Query(
        """
        SELECT COUNT(*) FROM discovered_items
        WHERE id = :id
          AND processingLeaseToken = :runToken
          AND processingLeaseExpiresAtEpochMillis IS NOT NULL
          AND processingLeaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun owns(id: String, runToken: String, atEpochMillis: Long): Int

    @Query(
        """
        UPDATE discovered_items SET processingLeaseToken = NULL, processingLeaseExpiresAtEpochMillis = NULL
        WHERE id = :id AND processingLeaseToken = :runToken
        """,
    )
    suspend fun release(id: String, runToken: String): Int

    @Query(
        """
        UPDATE raw_contents SET
            contentType = :contentType,
            payload = :payload,
            resolvedUrl = :resolvedUrl,
            fetchedAtEpochMillis = :fetchedAtEpochMillis,
            httpStatus = :httpStatus,
            expiresAtEpochMillis = :expiresAtEpochMillis
        WHERE discoveredItemId = :id
        """,
    )
    suspend fun updateRaw(
        id: String,
        contentType: String?,
        payload: ByteArray,
        resolvedUrl: String?,
        fetchedAtEpochMillis: Long,
        httpStatus: Int,
        expiresAtEpochMillis: Long?,
    ): Int

    @Insert
    suspend fun insertRaw(content: RawContentEntity)

    @Query("SELECT * FROM raw_contents WHERE discoveredItemId = :id LIMIT 1")
    suspend fun raw(id: String): RawContentEntity?

    @Query("DELETE FROM raw_contents WHERE discoveredItemId = :id")
    suspend fun deleteRaw(id: String)

    @Transaction
    suspend fun storeRawOwned(id: String, runToken: String, atEpochMillis: Long, content: RawContentEntity): Boolean {
        if (owns(id, runToken, atEpochMillis) != 1) return false
        require(content.discoveredItemId == id)
        val updated = updateRaw(
            id,
            content.contentType,
            content.payload,
            content.resolvedUrl,
            content.fetchedAtEpochMillis,
            content.httpStatus,
            content.expiresAtEpochMillis,
        )
        if (updated == 0) insertRaw(content)
        return true
    }

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
          AND processingLeaseToken = :runToken
          AND processingLeaseExpiresAtEpochMillis IS NOT NULL
          AND processingLeaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun markFetchedOwned(id: String, runToken: String, atEpochMillis: Long, canonicalUrl: String, resolvedUrl: String?, contentHash: String): Int

    @Query(
        """
        UPDATE discovered_items SET
            status = 'FAILED',
            processingAttempts = :processingAttempts,
            nextProcessingAtEpochMillis = :nextProcessingAtEpochMillis,
            lastProcessingError = :lastProcessingError,
            processingLeaseToken = NULL,
            processingLeaseExpiresAtEpochMillis = NULL
        WHERE id = :id
          AND processingLeaseToken = :runToken
          AND processingLeaseExpiresAtEpochMillis IS NOT NULL
          AND processingLeaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun markFailedOwned(
        id: String,
        runToken: String,
        atEpochMillis: Long,
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
            lastProcessingError = :lastProcessingError,
            processingLeaseToken = NULL,
            processingLeaseExpiresAtEpochMillis = NULL
        WHERE id = :id
          AND processingLeaseToken = :runToken
          AND processingLeaseExpiresAtEpochMillis IS NOT NULL
          AND processingLeaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun markSkippedOwned(id: String, runToken: String, atEpochMillis: Long, canonicalUrl: String?, lastProcessingError: String): Int

    @Transaction
    suspend fun skipOwned(id: String, runToken: String, atEpochMillis: Long, canonicalUrl: String?, lastProcessingError: String): Boolean {
        if (markSkippedOwned(id, runToken, atEpochMillis, canonicalUrl, lastProcessingError) != 1) return false
        deleteRaw(id)
        return true
    }

    @Query("SELECT * FROM documents WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun documentByCanonicalUrl(canonicalUrl: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE contentHash = :contentHash ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun documentByContentHash(contentHash: String): DocumentEntity?

    @Query("SELECT * FROM inbox_items WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun inboxByCanonicalUrl(canonicalUrl: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items WHERE contentHash = :contentHash ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun inboxByContentHash(contentHash: String): InboxItemEntity?

    @Query(
        """
        SELECT * FROM seen_fingerprints
        WHERE disposition IN ('REJECTED', 'READ_AND_DISCARDED')
          AND (canonicalUrlHash = :canonicalUrlHash OR contentHash = :contentHash)
        ORDER BY seenAtEpochMillis DESC
        LIMIT 1
        """,
    )
    suspend fun dismissedFingerprint(canonicalUrlHash: String, contentHash: String): SeenFingerprintEntity?

    @Upsert suspend fun upsertInboxItem(item: InboxItemEntity)
    @Upsert suspend fun upsertInboxOrigin(origin: InboxOriginEntity)
    @Upsert suspend fun upsertProvenance(provenance: DocumentProvenanceEntity)
    @Upsert suspend fun upsertFingerprint(fingerprint: SeenFingerprintEntity)

    @Query(
        """
        UPDATE discovered_items SET
            canonicalUrl = COALESCE(:canonicalUrl, canonicalUrl),
            resolvedUrl = COALESCE(:resolvedUrl, resolvedUrl),
            contentHash = :contentHash,
            status = 'PROCESSED',
            nextProcessingAtEpochMillis = NULL,
            lastProcessingError = NULL,
            processingLeaseToken = NULL,
            processingLeaseExpiresAtEpochMillis = NULL
        WHERE id = :id
          AND processingLeaseToken = :runToken
          AND processingLeaseExpiresAtEpochMillis IS NOT NULL
          AND processingLeaseExpiresAtEpochMillis > :atEpochMillis
        """,
    )
    suspend fun finishProcessedOwned(
        id: String,
        runToken: String,
        atEpochMillis: Long,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
    ): Int

    @Transaction
    suspend fun finalizeSuccess(
        id: String,
        runToken: String,
        atEpochMillis: Long,
        item: InboxItemEntity,
        origin: InboxOriginEntity,
        canonicalUrlHash: String,
    ): IngestionFinalizeOutcome {
        if (owns(id, runToken, atEpochMillis) != 1) return IngestionFinalizeOutcome.STALE
        require(origin.discoveredItemId == id)

        val byUrl = documentByCanonicalUrl(item.canonicalUrl)
        val byContent = documentByContentHash(item.contentHash)
        // Same canonical URL is only "known" when the saved representation is unchanged. A changed
        // representation for the same material must reach Inbox so Save can append DocumentVersion.
        val unchangedDocument = when {
            byUrl != null && byUrl.contentHash == item.contentHash -> byUrl
            byUrl == null && byContent != null -> byContent
            else -> null
        }

        val outcome = if (unchangedDocument != null) {
            upsertProvenance(
                DocumentProvenanceEntity(
                    documentId = unchangedDocument.id,
                    originKey = "${origin.sourceId}|${origin.discoveredUrl}",
                    sourceId = origin.sourceId,
                    discoveredUrl = origin.discoveredUrl,
                    resolvedUrl = origin.resolvedUrl,
                    discoveredAtEpochMillis = origin.discoveredAtEpochMillis,
                    fetchedAtEpochMillis = origin.fetchedAtEpochMillis,
                    sourceNameSnapshot = origin.sourceNameSnapshot,
                    sourceUrlSnapshot = origin.sourceUrlSnapshot,
                    sourceTypeSnapshot = origin.sourceTypeSnapshot,
                ),
            )
            upsertFingerprint(
                SeenFingerprintEntity(
                    canonicalUrlHash = canonicalUrlHash,
                    contentHash = item.contentHash,
                    sourceId = origin.sourceId,
                    seenAtEpochMillis = atEpochMillis,
                    disposition = "SAVED",
                ),
            )
            IngestionFinalizeOutcome.ALREADY_KNOWN
        } else {
            val dismissed = dismissedFingerprint(canonicalUrlHash, item.contentHash)
            if (dismissed != null) {
                upsertFingerprint(
                    SeenFingerprintEntity(
                        canonicalUrlHash = canonicalUrlHash,
                        contentHash = item.contentHash,
                        sourceId = origin.sourceId,
                        seenAtEpochMillis = atEpochMillis,
                        disposition = dismissed.disposition,
                    ),
                )
                IngestionFinalizeOutcome.ALREADY_KNOWN
            } else {
                val inboxByUrl = inboxByCanonicalUrl(item.canonicalUrl)
                val inboxByContent = inboxByContentHash(item.contentHash)
                when {
                    inboxByUrl != null && inboxByUrl.contentHash != item.contentHash -> {
                        // One pending row per canonical material: advance it to the newest observed
                        // representation while retaining its first-created timestamp and all origins.
                        upsertInboxItem(
                            item.copy(
                                id = inboxByUrl.id,
                                createdAtEpochMillis = inboxByUrl.createdAtEpochMillis,
                                updatedAtEpochMillis = atEpochMillis,
                            ),
                        )
                        upsertInboxOrigin(origin.copy(inboxItemId = inboxByUrl.id))
                        IngestionFinalizeOutcome.MERGED_INTO_INBOX
                    }
                    inboxByUrl != null -> {
                        upsertInboxOrigin(origin.copy(inboxItemId = inboxByUrl.id))
                        IngestionFinalizeOutcome.MERGED_INTO_INBOX
                    }
                    inboxByContent != null -> {
                        upsertInboxOrigin(origin.copy(inboxItemId = inboxByContent.id))
                        IngestionFinalizeOutcome.MERGED_INTO_INBOX
                    }
                    else -> {
                        upsertInboxItem(item)
                        upsertInboxOrigin(origin)
                        IngestionFinalizeOutcome.ADDED_TO_INBOX
                    }
                }
            }
        }

        check(
            finishProcessedOwned(
                id = id,
                runToken = runToken,
                atEpochMillis = atEpochMillis,
                canonicalUrl = item.canonicalUrl,
                resolvedUrl = origin.resolvedUrl,
                contentHash = item.contentHash,
            ) == 1,
        ) { "Article processing lease changed during finalisation" }
        deleteRaw(id)
        return outcome
    }
}
