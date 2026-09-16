package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import java.security.MessageDigest

@Dao
interface InboxLifecycleDao {
    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun item(id: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_origins WHERE inboxItemId = :id ORDER BY discoveredAtEpochMillis, discoveredItemId")
    suspend fun origins(id: String): List<InboxOriginEntity>

    @Query("SELECT * FROM documents WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun documentByCanonicalUrl(canonicalUrl: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE contentHash = :contentHash ORDER BY createdAtEpochMillis LIMIT 1")
    suspend fun documentByContentHash(contentHash: String): DocumentEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM document_versions WHERE documentId = :documentId")
    suspend fun maxVersion(documentId: String): Long

    @Upsert
    suspend fun upsertDocument(document: DocumentEntity)

    /** Existing saved parent rows must be updated in place so FK children cannot be deleted/recreated. */
    @Update
    suspend fun updateDocument(document: DocumentEntity): Int

    @Upsert
    suspend fun upsertVersion(version: DocumentVersionEntity)

    @Upsert
    suspend fun upsertProvenances(provenances: List<DocumentProvenanceEntity>)

    @Upsert
    suspend fun upsertFingerprints(fingerprints: List<SeenFingerprintEntity>)

    @Query(
        """
        UPDATE discovered_items SET
            status = 'PROCESSED',
            nextProcessingAtEpochMillis = NULL,
            lastProcessingError = NULL,
            processingLeaseToken = NULL,
            processingLeaseExpiresAtEpochMillis = NULL
        WHERE id IN (:ids)
        """,
    )
    suspend fun finishDiscoveries(ids: List<String>)

    @Query("DELETE FROM raw_contents WHERE discoveredItemId IN (:ids)")
    suspend fun deleteRaw(ids: List<String>)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteInbox(id: String): Int

    /**
     * Returns the saved/existing Document id, or null if another committed action consumed Inbox.
     * All current origins are read after this transaction begins, so none can be lost by a stale
     * service-layer snapshot.
     *
     * [parserVersion] is retained in the API for source compatibility only. The durable version is
     * the parserVersion stored with the Inbox row when extraction completed; Save must never relabel
     * previously extracted text/structure with the currently running application parser.
     */
    @Transaction
    suspend fun saveCurrent(
        inboxId: String,
        atEpochMillis: Long,
        parserVersion: String,
    ): String? {
        val inbox = item(inboxId) ?: return null
        val currentOrigins = origins(inboxId)
        check(currentOrigins.isNotEmpty()) { "Inbox item $inboxId has no provenance" }

        val existing = documentByCanonicalUrl(inbox.canonicalUrl) ?: documentByContentHash(inbox.contentHash)
        val documentId: String
        if (existing == null) {
            documentId = "doc-${sha256(inbox.canonicalUrl)}"
            upsertDocument(
                DocumentEntity(
                    id = documentId,
                    canonicalUrl = inbox.canonicalUrl,
                    title = inbox.title,
                    author = null,
                    publishedAtEpochMillis = inbox.publishedAtEpochMillis,
                    language = null,
                    normalizedText = inbox.normalizedText,
                    contentHash = inbox.contentHash,
                    createdAtEpochMillis = atEpochMillis,
                    updatedAtEpochMillis = atEpochMillis,
                    disposition = "SAVED",
                    structuredContentFormat = inbox.structuredContentFormat,
                    structuredContent = inbox.structuredContent,
                ),
            )
            upsertVersion(
                DocumentVersionEntity(
                    documentId = documentId,
                    version = 1,
                    contentHash = inbox.contentHash,
                    normalizedText = inbox.normalizedText,
                    fetchedAtEpochMillis = currentOrigins.maxOf { it.fetchedAtEpochMillis },
                    parserVersion = inbox.parserVersion,
                    structuredContentFormat = inbox.structuredContentFormat,
                    structuredContent = inbox.structuredContent,
                ),
            )
        } else {
            documentId = existing.id
            if (existing.contentHash != inbox.contentHash) {
                check(
                    updateDocument(
                        existing.copy(
                            canonicalUrl = inbox.canonicalUrl,
                            title = inbox.title,
                            publishedAtEpochMillis = inbox.publishedAtEpochMillis,
                            normalizedText = inbox.normalizedText,
                            contentHash = inbox.contentHash,
                            updatedAtEpochMillis = atEpochMillis,
                            disposition = "SAVED",
                            structuredContentFormat = inbox.structuredContentFormat,
                            structuredContent = inbox.structuredContent,
                        ),
                    ) == 1,
                ) { "Saved document $documentId changed during Inbox save" }
                upsertVersion(
                    DocumentVersionEntity(
                        documentId = documentId,
                        version = maxVersion(documentId) + 1,
                        contentHash = inbox.contentHash,
                        normalizedText = inbox.normalizedText,
                        fetchedAtEpochMillis = currentOrigins.maxOf { it.fetchedAtEpochMillis },
                        parserVersion = inbox.parserVersion,
                        structuredContentFormat = inbox.structuredContentFormat,
                        structuredContent = inbox.structuredContent,
                    ),
                )
            }
        }

        upsertProvenances(
            currentOrigins.map { origin ->
                DocumentProvenanceEntity(
                    documentId = documentId,
                    originKey = "${origin.sourceId}|${origin.discoveredUrl}",
                    sourceId = origin.sourceId,
                    discoveredUrl = origin.discoveredUrl,
                    resolvedUrl = origin.resolvedUrl,
                    discoveredAtEpochMillis = origin.discoveredAtEpochMillis,
                    fetchedAtEpochMillis = origin.fetchedAtEpochMillis,
                    sourceNameSnapshot = origin.sourceNameSnapshot,
                    sourceUrlSnapshot = origin.sourceUrlSnapshot,
                    sourceTypeSnapshot = origin.sourceTypeSnapshot,
                )
            },
        )
        upsertFingerprints(
            currentOrigins.map { origin ->
                SeenFingerprintEntity(
                    canonicalUrlHash = sha256(origin.canonicalUrl),
                    contentHash = inbox.contentHash,
                    sourceId = origin.sourceId,
                    seenAtEpochMillis = atEpochMillis,
                    disposition = "SAVED",
                )
            }.distinctBy { it.canonicalUrlHash to it.sourceId },
        )

        val discoveryIds = currentOrigins.map { it.discoveredItemId }
        finishDiscoveries(discoveryIds)
        deleteRaw(discoveryIds)
        check(deleteInbox(inboxId) == 1) { "Inbox item $inboxId changed during save transaction" }
        return documentId
    }

    /** Returns false if Save/Reject/ReadAndDiscard already consumed this Inbox row. */
    @Transaction
    suspend fun discardCurrent(
        inboxId: String,
        disposition: String,
        atEpochMillis: Long,
    ): Boolean {
        require(disposition == "REJECTED" || disposition == "READ_AND_DISCARDED")
        val inbox = item(inboxId) ?: return false
        val currentOrigins = origins(inboxId)
        check(currentOrigins.isNotEmpty()) { "Inbox item $inboxId has no provenance" }

        upsertFingerprints(
            currentOrigins.map { origin ->
                SeenFingerprintEntity(
                    canonicalUrlHash = sha256(origin.canonicalUrl),
                    contentHash = inbox.contentHash,
                    sourceId = origin.sourceId,
                    seenAtEpochMillis = atEpochMillis,
                    disposition = disposition,
                )
            }.distinctBy { it.canonicalUrlHash to it.sourceId },
        )
        val discoveryIds = currentOrigins.map { it.discoveredItemId }
        finishDiscoveries(discoveryIds)
        deleteRaw(discoveryIds)
        check(deleteInbox(inboxId) == 1) { "Inbox item $inboxId changed during discard transaction" }
        return true
    }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
