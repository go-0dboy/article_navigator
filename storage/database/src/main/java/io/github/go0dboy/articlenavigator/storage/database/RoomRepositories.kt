package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.data.CollectionStateRepository
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.KnowledgeRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
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

class RoomSourceRepository(private val dao: SourceDao) : SourceRepository {
    override suspend fun upsert(source: Source) = dao.upsert(source.toEntity())
    override suspend fun findById(id: SourceId): Source? = dao.findById(id.value)?.toDomain()
    override suspend fun findDue(now: Instant): List<Source> = dao.findDue(now.toEpochMilli()).map { it.toDomain() }
    override suspend fun listAll(): List<Source> = dao.listAll().map { it.toDomain() }
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = dao.findCursor(sourceId.value)?.toDomain()
    override suspend fun saveCursor(cursor: SourceCursor) = dao.upsertCursor(cursor.toEntity())
}

class RoomCollectionStateRepository(private val dao: CollectionStateDao) : CollectionStateRepository {
    override suspend fun load(sourceId: SourceId): SourceCollectionState? = dao.findBySourceId(sourceId.value)?.toDomain()
    override suspend fun save(state: SourceCollectionState) = dao.upsert(state.toEntity())
}

class RoomIngestionRepository(private val dao: IngestionDao) : IngestionRepository {
    override suspend fun upsertDiscovered(item: DiscoveredItem) = dao.upsertDiscovered(item.toEntity())
    override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = dao.findDiscoveredById(id.value)?.toDomain()
    override suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem? = dao.findDiscovered(sourceId.value, url)?.toDomain()
    override suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem> =
        dao.findReadyForProcessing(now.toEpochMilli(), limit).map { it.toDomain() }
    override suspend fun storeRawContent(content: RawContent) = dao.upsertRawContent(content.toEntity())
    override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = dao.findRawContent(id.value)?.toDomain()
    override suspend fun deleteRawContent(id: DiscoveredItemId) = dao.deleteRawContent(id.value)
}

class RoomInboxRepository(private val dao: InboxDao) : InboxRepository {
    override suspend fun put(item: InboxItem, origin: InboxOrigin) = dao.put(item.toEntity(), origin.toEntity())
    override suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin) =
        dao.attachOrigin(itemId.value, origin.toEntity())
    override suspend fun listPending(limit: Int): List<InboxItem> = dao.listPending(limit).map { it.toDomain() }
    override suspend fun findById(id: InboxItemId): InboxItem? = dao.findById(id.value)?.toDomain()
    override suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem? = dao.findByCanonicalUrl(canonicalUrl)?.toDomain()
    override suspend fun findByContentHash(contentHash: String): InboxItem? = dao.findByContentHash(contentHash)?.toDomain()
    override suspend fun origins(id: InboxItemId): List<InboxOrigin> = dao.origins(id.value).map { it.toDomain() }
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
