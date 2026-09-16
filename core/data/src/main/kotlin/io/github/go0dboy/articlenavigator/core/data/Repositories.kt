package io.github.go0dboy.articlenavigator.core.data

import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.time.Instant

interface SourceRepository {
    /** Creation/import path. Scheduler code must not use full-row upsert for operational updates. */
    suspend fun upsert(source: Source)
    suspend fun findById(id: SourceId): Source?
    suspend fun findDue(now: Instant): List<Source>
    suspend fun findDue(now: Instant, limit: Int): List<Source> = findDue(now).take(limit)
    suspend fun listAll(): List<Source>
    suspend fun loadCursor(sourceId: SourceId): SourceCursor?
    suspend fun saveCursor(cursor: SourceCursor)
    /** Operational update only: does not mutate user settings or settingsRevision. */
    suspend fun markDue(sourceId: SourceId, at: Instant): Boolean
}

interface CollectionStateRepository {
    suspend fun load(sourceId: SourceId): SourceCollectionState?
    suspend fun save(state: SourceCollectionState)
}

/**
 * Exclusive, expiring ownership of one source collection attempt.
 * Network work happens while this lease is held but outside any DB transaction.
 */
data class SourceCollectionLease(
    val source: Source,
    val cursor: SourceCursor?,
    val previousState: SourceCollectionState?,
    val runToken: String,
    val settingsRevision: Long,
    val expiresAt: Instant,
)

enum class CollectionCommitOutcome {
    APPLIED,
    STALE,
}

/**
 * Atomic boundary for collection scheduling state. Implementations must:
 * - claim with a compare-and-set update;
 * - commit discovery + cursor + schedule + diagnostics in one transaction;
 * - reject commits from expired/replaced leases or changed source settings.
 */
interface CollectionRepository {
    suspend fun tryClaim(
        sourceId: SourceId,
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): SourceCollectionLease?

    suspend fun commitSuccess(
        lease: SourceCollectionLease,
        items: List<DiscoveredItem>,
        cursor: SourceCursor,
        completedAt: Instant,
        nextCheckAt: Instant,
        discoveredCount: Int,
    ): CollectionCommitOutcome

    suspend fun commitFailure(
        lease: SourceCollectionLease,
        completedAt: Instant,
        nextCheckAt: Instant,
        errorType: String,
        errorMessage: String?,
    ): CollectionCommitOutcome

    suspend fun release(lease: SourceCollectionLease)
}

/** Persisted ownership of one article-processing attempt. */
data class ArticleProcessingLease(
    val item: DiscoveredItem,
    val runToken: String,
    val expiresAt: Instant,
)

enum class IngestionFinalizeOutcome {
    ADDED_TO_INBOX,
    MERGED_INTO_INBOX,
    ALREADY_KNOWN,
    STALE,
}

/**
 * Article processing uses an expiring persisted lease. The lease does not promise that an old
 * network socket is physically stopped the instant the lease expires; it promises that only the
 * current owner may persist raw/intermediate/final results. Replacement owners therefore remain
 * safe even when an older HTTP call finishes late.
 */
interface IngestionRepository {
    /** Creation/import/test seeding path. Runtime processing transitions must use ownership methods. */
    suspend fun upsertDiscovered(item: DiscoveredItem)
    suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem?
    suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem?
    suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem>

    /**
     * Atomically selects and claims the next eligible item. Network policy is applied before the
     * limit/selection, so queued unmetered-only work cannot starve eligible work on metered links.
     */
    suspend fun tryClaimNext(
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
        isUnmeteredNetwork: Boolean,
    ): ArticleProcessingLease? = findReadyForProcessing(now, 1).firstOrNull()?.let {
        ArticleProcessingLease(it, runToken, leaseExpiresAt)
    }

    suspend fun releaseProcessing(lease: ArticleProcessingLease) = Unit

    /** Ownership-guarded raw response persistence. */
    suspend fun storeRawContent(
        lease: ArticleProcessingLease,
        content: RawContent,
        at: Instant,
    ): Boolean {
        storeRawContent(content)
        return true
    }

    suspend fun loadRawContent(lease: ArticleProcessingLease): RawContent? = loadRawContent(lease.item.id)

    /** Ownership-guarded intermediate transition. */
    suspend fun markFetched(
        lease: ArticleProcessingLease,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
        at: Instant,
    ): Boolean = markFetched(lease.item.id, canonicalUrl, resolvedUrl, contentHash)

    /** Ownership-guarded retry transition; a successful call releases the processing lease. */
    suspend fun markFailed(
        lease: ArticleProcessingLease,
        processingAttempts: Int,
        nextProcessingAt: Instant,
        lastProcessingError: String,
        at: Instant,
    ): Boolean = markFailed(lease.item.id, processingAttempts, nextProcessingAt, lastProcessingError)

    /** Ownership-guarded terminal skip; a successful call removes temporary raw content. */
    suspend fun markSkipped(
        lease: ArticleProcessingLease,
        canonicalUrl: String?,
        lastProcessingError: String,
        at: Instant,
    ): Boolean = markSkipped(lease.item.id, canonicalUrl, lastProcessingError)

    /**
     * Single transactional finalisation boundary for deduplication and Inbox/Knowledge writes.
     * Implementations must re-check processing ownership inside the transaction.
     */
    suspend fun finalizeSuccess(
        lease: ArticleProcessingLease,
        item: InboxItem,
        origin: InboxOrigin,
        canonicalUrlHash: String,
        completedAt: Instant,
    ): IngestionFinalizeOutcome = error("Atomic ingestion finalisation is not implemented")

    /** Legacy/test helpers. Production processing must use the ownership-aware overloads above. */
    suspend fun markProcessed(id: DiscoveredItemId, canonicalUrl: String?): Boolean
    suspend fun markFetched(
        id: DiscoveredItemId,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
    ): Boolean
    suspend fun markFailed(
        id: DiscoveredItemId,
        processingAttempts: Int,
        nextProcessingAt: Instant,
        lastProcessingError: String,
    ): Boolean
    suspend fun markSkipped(id: DiscoveredItemId, canonicalUrl: String?, lastProcessingError: String): Boolean

    suspend fun storeRawContent(content: RawContent)
    suspend fun loadRawContent(id: DiscoveredItemId): RawContent?
    suspend fun deleteRawContent(id: DiscoveredItemId)
}

interface InboxRepository {
    /**
     * Atomically stages a fetched item in Inbox and commits its discovery origin.
     * Runtime ingestion now uses [IngestionRepository.finalizeSuccess]; this path remains for
     * import/tests and must not be used to bypass processing ownership.
     */
    suspend fun put(item: InboxItem, origin: InboxOrigin)

    suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin)

    suspend fun listPending(limit: Int = 100): List<InboxItem>
    suspend fun findById(id: InboxItemId): InboxItem?
    suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem?
    suspend fun findByContentHash(contentHash: String): InboxItem?
    suspend fun origins(id: InboxItemId): List<InboxOrigin>

    /**
     * Reads the current Inbox row and every current origin, resolves any existing Document,
     * persists Document/version/provenance/fingerprints, and removes Inbox in one DB transaction.
     * Returns null when another committed action already consumed the Inbox row.
     */
    suspend fun saveCurrent(
        id: InboxItemId,
        at: Instant,
        parserVersion: String,
    ): DocumentId?

    /**
     * Reads current item/origins, persists all dismissal fingerprints, and removes Inbox in one
     * transaction. Returns false when the row was already consumed by another committed action.
     */
    suspend fun discardCurrent(
        id: InboxItemId,
        disposition: ContentDisposition,
        at: Instant,
    ): Boolean

    /** Legacy/import helpers; UI/runtime user actions must use saveCurrent/discardCurrent. */
    suspend fun discard(
        id: InboxItemId,
        disposition: ContentDisposition,
        fingerprints: List<SeenFingerprint>,
    )

    suspend fun save(
        id: InboxItemId,
        document: Document,
        version: DocumentVersion,
        provenances: List<DocumentProvenance>,
        fingerprints: List<SeenFingerprint>,
    )
}

interface KnowledgeRepository {
    suspend fun persist(
        document: Document,
        version: DocumentVersion,
        provenance: DocumentProvenance,
        fingerprint: SeenFingerprint,
    )

    suspend fun findById(id: DocumentId): Document?
    suspend fun findByCanonicalUrl(canonicalUrl: String): Document?
    suspend fun findByContentHash(contentHash: String): Document?
    suspend fun versions(documentId: DocumentId): List<DocumentVersion>
    suspend fun provenance(documentId: DocumentId): List<DocumentProvenance>
    suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint?
    suspend fun recordDiscovery(
        documentId: DocumentId,
        provenance: DocumentProvenance,
        fingerprint: SeenFingerprint,
        discoveredItemId: DiscoveredItemId,
    )
    suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant)
}
