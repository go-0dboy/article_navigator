package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.data.ArticleProcessingLease
import io.github.go0dboy.articlenavigator.core.data.CollectionCommitOutcome
import io.github.go0dboy.articlenavigator.core.data.CollectionRepository
import io.github.go0dboy.articlenavigator.core.data.CollectionStateRepository
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.KnowledgeRepository
import io.github.go0dboy.articlenavigator.core.data.SourceCollectionLease
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
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

class RoomSourceRepository(
    private val dao: SourceDao,
    private val scheduleDao: SourceScheduleDao,
) : SourceRepository {
    override suspend fun upsert(source: Source) = dao.saveUserSource(source.toEntity())
    override suspend fun findById(id: SourceId): Source? = dao.findById(id.value)?.toDomain()
    override suspend fun findDue(now: Instant): List<Source> = dao.findDue(now.toEpochMilli()).map { it.toDomain() }
    override suspend fun findDue(now: Instant, limit: Int): List<Source> =
        dao.findDue(now.toEpochMilli(), limit).map { it.toDomain() }
    override suspend fun listAll(): List<Source> = dao.listAll().map { it.toDomain() }
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = dao.findCursor(sourceId.value)?.toDomain()
    override suspend fun saveCursor(cursor: SourceCursor) = dao.upsertCursor(cursor.toEntity())
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean =
        scheduleDao.markDue(sourceId.value, at.toEpochMilli()) == 1
}

class RoomCollectionStateRepository(private val dao: CollectionStateDao) : CollectionStateRepository {
    override suspend fun load(sourceId: SourceId): SourceCollectionState? = dao.findBySourceId(sourceId.value)?.toDomain()
    override suspend fun save(state: SourceCollectionState) = dao.upsert(state.toEntity())
}

class RoomCollectionRepository(private val dao: CollectionDao) : CollectionRepository {
    override suspend fun tryClaim(
        sourceId: SourceId,
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
    ): SourceCollectionLease? {
        val snapshot = dao.tryClaim(
            sourceId = sourceId.value,
            runToken = runToken,
            nowEpochMillis = now.toEpochMilli(),
            leaseExpiresAtEpochMillis = leaseExpiresAt.toEpochMilli(),
        ) ?: return null
        val source = snapshot.source.toDomain()
        return SourceCollectionLease(
            source = source,
            cursor = snapshot.cursor?.toDomain(),
            previousState = snapshot.state?.toDomain(),
            runToken = runToken,
            settingsRevision = source.settingsRevision,
            expiresAt = leaseExpiresAt,
        )
    }

    override suspend fun commitSuccess(
        lease: SourceCollectionLease,
        items: List<DiscoveredItem>,
        cursor: SourceCursor,
        completedAt: Instant,
        nextCheckAt: Instant,
        discoveredCount: Int,
    ): CollectionCommitOutcome {
        val applied = dao.commitSuccess(
            sourceId = lease.source.id.value,
            runToken = lease.runToken,
            settingsRevision = lease.settingsRevision,
            items = items.map { it.toEntity() },
            nextCursor = cursor.toEntity(),
            state = SourceCollectionStateEntity(
                sourceId = lease.source.id.value,
                consecutiveFailures = 0,
                lastAttemptAtEpochMillis = completedAt.toEpochMilli(),
                lastErrorType = null,
                lastErrorMessage = null,
                lastDiscoveredCount = discoveredCount,
            ),
            completedAtEpochMillis = completedAt.toEpochMilli(),
            nextCheckAtEpochMillis = nextCheckAt.toEpochMilli(),
        )
        return if (applied) CollectionCommitOutcome.APPLIED else CollectionCommitOutcome.STALE
    }

    override suspend fun commitFailure(
        lease: SourceCollectionLease,
        completedAt: Instant,
        nextCheckAt: Instant,
        errorType: String,
        errorMessage: String?,
    ): CollectionCommitOutcome {
        val previous = lease.previousState
        val applied = dao.commitFailure(
            sourceId = lease.source.id.value,
            runToken = lease.runToken,
            settingsRevision = lease.settingsRevision,
            state = SourceCollectionStateEntity(
                sourceId = lease.source.id.value,
                consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                lastAttemptAtEpochMillis = completedAt.toEpochMilli(),
                lastErrorType = errorType,
                lastErrorMessage = errorMessage?.take(2_000),
                lastDiscoveredCount = previous?.lastDiscoveredCount ?: 0,
            ),
            completedAtEpochMillis = completedAt.toEpochMilli(),
            nextCheckAtEpochMillis = nextCheckAt.toEpochMilli(),
        )
        return if (applied) CollectionCommitOutcome.APPLIED else CollectionCommitOutcome.STALE
    }

    override suspend fun release(lease: SourceCollectionLease) {
        dao.release(lease.source.id.value, lease.runToken)
    }
}

class RoomIngestionRepository(
    private val dao: IngestionDao,
    private val processingDao: ArticleProcessingDao? = null,
) : IngestionRepository {
    private fun processing(): ArticleProcessingDao = checkNotNull(processingDao) {
        "ArticleProcessingDao is required for runtime ingestion ownership operations"
    }

    override suspend fun upsertDiscovered(item: DiscoveredItem) = dao.upsertDiscovered(item.toEntity())
    override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = dao.findDiscoveredById(id.value)?.toDomain()
    override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? = dao.findDiscovered(sourceId.value, url)?.toDomain()
    override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> =
        dao.findReadyForProcessing(now.toEpochMilli(), limit).map { it.toDomain() }

    override suspend fun tryClaimNext(
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
        isUnmeteredNetwork: Boolean,
    ): ArticleProcessingLease? = processing().tryClaimNext(
        runToken = runToken,
        nowEpochMillis = now.toEpochMilli(),
        leaseExpiresAtEpochMillis = leaseExpiresAt.toEpochMilli(),
        isUnmeteredNetwork = isUnmeteredNetwork,
    )?.let { ArticleProcessingLease(it.toDomain(), runToken, leaseExpiresAt) }

    override suspend fun releaseProcessing(lease: ArticleProcessingLease) {
        processing().release(lease.item.id.value, lease.runToken)
    }

    override suspend fun storeRawContent(
        lease: ArticleProcessingLease,
        content: RawContent,
        at: Instant,
    ): Boolean = processing().storeRawOwned(
        id = lease.item.id.value,
        runToken = lease.runToken,
        atEpochMillis = at.toEpochMilli(),
        content = content.toEntity(),
    )

    override suspend fun loadRawContent(lease: ArticleProcessingLease): RawContent? =
        processing().raw(lease.item.id.value)?.toDomain()

    override suspend fun markFetched(
        lease: ArticleProcessingLease,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
        at: Instant,
    ): Boolean = processing().markFetchedOwned(
        id = lease.item.id.value,
        runToken = lease.runToken,
        atEpochMillis = at.toEpochMilli(),
        canonicalUrl = canonicalUrl,
        resolvedUrl = resolvedUrl,
        contentHash = contentHash,
    ) == 1

    override suspend fun markFailed(
        lease: ArticleProcessingLease,
        processingAttempts: Int,
        nextProcessingAt: Instant,
        lastProcessingError: String,
        at: Instant,
    ): Boolean = processing().markFailedOwned(
        id = lease.item.id.value,
        runToken = lease.runToken,
        atEpochMillis = at.toEpochMilli(),
        processingAttempts = processingAttempts,
        nextProcessingAtEpochMillis = nextProcessingAt.toEpochMilli(),
        lastProcessingError = lastProcessingError,
    ) == 1

    override suspend fun markSkipped(
        lease: ArticleProcessingLease,
        canonicalUrl: String?,
        lastProcessingError: String,
        at: Instant,
    ): Boolean = processing().skipOwned(
        id = lease.item.id.value,
        runToken = lease.runToken,
        atEpochMillis = at.toEpochMilli(),
        canonicalUrl = canonicalUrl,
        lastProcessingError = lastProcessingError,
    )

    override suspend fun finalizeSuccess(
        lease: ArticleProcessingLease,
        item: InboxItem,
        origin: InboxOrigin,
        canonicalUrlHash: String,
        completedAt: Instant,
    ): IngestionFinalizeOutcome = processing().finalizeSuccess(
        id = lease.item.id.value,
        runToken = lease.runToken,
        atEpochMillis = completedAt.toEpochMilli(),
        item = item.toEntity(),
        origin = origin.toEntity(),
        canonicalUrlHash = canonicalUrlHash,
    )

    /** Legacy transitions are terminal-state guarded; runtime uses lease-aware methods above. */
    override suspend fun markProcessed(id: DiscoveredItemId, canonicalUrl: String?): Boolean {
        val current = dao.findDiscoveredById(id.value)?.toDomain() ?: return false
        if (current.status == DiscoveryStatus.PROCESSED || current.status == DiscoveryStatus.SKIPPED) return false
        return dao.markProcessed(id.value, canonicalUrl) == 1
    }

    override suspend fun markFetched(
        id: DiscoveredItemId,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
    ): Boolean {
        val current = dao.findDiscoveredById(id.value)?.toDomain() ?: return false
        if (current.status == DiscoveryStatus.PROCESSED || current.status == DiscoveryStatus.SKIPPED) return false
        return dao.markFetched(id.value, canonicalUrl, resolvedUrl, contentHash) == 1
    }

    override suspend fun markFailed(
        id: DiscoveredItemId,
        processingAttempts: Int,
        nextProcessingAt: Instant,
        lastProcessingError: String,
    ): Boolean {
        val current = dao.findDiscoveredById(id.value)?.toDomain() ?: return false
        if (current.status == DiscoveryStatus.PROCESSED || current.status == DiscoveryStatus.SKIPPED) return false
        return dao.markFailed(
            id = id.value,
            processingAttempts = processingAttempts,
            nextProcessingAtEpochMillis = nextProcessingAt.toEpochMilli(),
            lastProcessingError = lastProcessingError,
        ) == 1
    }

    override suspend fun markSkipped(
        id: DiscoveredItemId,
        canonicalUrl: String?,
        lastProcessingError: String,
    ): Boolean {
        val current = dao.findDiscoveredById(id.value)?.toDomain() ?: return false
        if (current.status == DiscoveryStatus.PROCESSED || current.status == DiscoveryStatus.SKIPPED) return false
        return dao.markSkipped(id.value, canonicalUrl, lastProcessingError) == 1
    }

    override suspend fun storeRawContent(content: RawContent) = dao.upsertRawContent(content.toEntity())
    override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = dao.findRawContent(id.value)?.toDomain()
    override suspend fun deleteRawContent(id: DiscoveredItemId) = dao.deleteRawContent(id.value)
}

class RoomInboxRepository(
    private val dao: InboxDao,
    private val lifecycleDao: InboxLifecycleDao? = null,
) : InboxRepository {
    private fun lifecycle(): InboxLifecycleDao = checkNotNull(lifecycleDao) {
        "InboxLifecycleDao is required for runtime Inbox user actions"
    }

    override suspend fun put(item: InboxItem, origin: InboxOrigin) = dao.put(item.toEntity(), origin.toEntity())
    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) = dao.attachOrigin(itemId.value, origin.toEntity())
    override suspend fun listPending(limit: Int): List<InboxItem> = dao.listPending(limit).map { it.toDomain() }
    override suspend fun findById(id: InboxItemId): InboxItem? = dao.findById(id.value)?.toDomain()
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? = dao.findByCanonicalUrl(canonicalUrl)?.toDomain()
    override suspend fun findByContentHash(contentHash: String): InboxItem? = dao.findByContentHash(contentHash)?.toDomain()
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> = dao.origins(id.value).map { it.toDomain() }

    override suspend fun saveCurrent(id: InboxItemId, at: Instant, parserVersion: String): DocumentId? =
        lifecycle().saveCurrent(id.value, at.toEpochMilli(), parserVersion)?.let(::DocumentId)

    override suspend fun discardCurrent(
        id: InboxItemId,
        disposition: ContentDisposition,
        at: Instant,
    ): Boolean {
        require(disposition == ContentDisposition.REJECTED || disposition == ContentDisposition.READ_AND_DISCARDED)
        return lifecycle().discardCurrent(id.value, disposition.name, at.toEpochMilli())
    }

    override suspend fun discard(
        id: InboxItemId,
        disposition: ContentDisposition,
        fingerprints: List<SeenFingerprint>,
    ) {
        require(disposition == ContentDisposition.REJECTED || disposition == ContentDisposition.READ_AND_DISCARDED)
        dao.discard(id.value, fingerprints.map { it.toEntity() })
    }

    override suspend fun save(
        id: InboxItemId,
        document: Document,
        version: DocumentVersion,
        provenances: List<DocumentProvenance>,
        fingerprints: List<SeenFingerprint>,
    ) = dao.save(
        id.value,
        document.toEntity(),
        version.toEntity(),
        provenances.map { it.toEntity() },
        fingerprints.map { it.toEntity() },
    )
}

class RoomKnowledgeRepository(private val dao: DocumentDao) : KnowledgeRepository {
    override suspend fun persist(
        document: Document,
        version: DocumentVersion,
        provenance: DocumentProvenance,
        fingerprint: SeenFingerprint,
    ) = dao.persist(document.toEntity(), version.toEntity(), provenance.toEntity(), fingerprint.toEntity())

    override suspend fun findById(id: DocumentId): Document? = dao.findById(id.value)?.toDomain()
    override suspend fun findByCanonicalUrl(canonicalUrl: String): Document? = dao.findByCanonicalUrl(canonicalUrl)?.toDomain()
    override suspend fun findByContentHash(contentHash: String): Document? = dao.findByContentHash(contentHash)?.toDomain()
    override suspend fun versions(documentId: DocumentId): List<DocumentVersion> = dao.versions(documentId.value).map { it.toDomain() }
    override suspend fun provenance(documentId: DocumentId): List<DocumentProvenance> = dao.provenance(documentId.value).map { it.toDomain() }
    override suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint? =
        dao.findFingerprint(canonicalUrlHash, sourceId.value)?.toDomain()

    override suspend fun recordDiscovery(
        documentId: DocumentId,
        provenance: DocumentProvenance,
        fingerprint: SeenFingerprint,
        discoveredItemId: DiscoveredItemId,
    ) {
        require(documentId == provenance.documentId)
        dao.recordDiscovery(provenance.toEntity(), fingerprint.toEntity(), discoveredItemId.value)
    }

    override suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant) =
        dao.updateDisposition(id.value, disposition.name, updatedAt.toEpochMilli())
}
