package io.github.go0dboy.articlenavigator.storage.database

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
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.time.Instant

class RoomSourceRepository(
    private val dao: SourceDao,
) : SourceRepository {
    override suspend fun upsert(source: Source) = dao.upsert(source.toEntity())
    override suspend fun findById(id: SourceId): Source? = dao.findById(id.value)?.toDomain()
    override suspend fun findDue(now: Instant): List<Source> = dao.findDue(now.toEpochMilli()).map { it.toDomain() }
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = dao.findCursor(sourceId.value)?.toDomain()
    override suspend fun saveCursor(cursor: SourceCursor) = dao.upsertCursor(cursor.toEntity())
}

class RoomIngestionRepository(
    private val dao: IngestionDao,
) : IngestionRepository {
    override suspend fun upsertDiscovered(item: DiscoveredItem) = dao.upsertDiscovered(item.toEntity())
    override suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem? = dao.findDiscoveredById(id.value)?.toDomain()
    override suspend fun storeRawContent(content: RawContent) = dao.upsertRawContent(content.toEntity())
    override suspend fun loadRawContent(id: DiscoveredItemId): RawContent? = dao.findRawContent(id.value)?.toDomain()
}

class RoomKnowledgeRepository(
    private val dao: DocumentDao,
) : KnowledgeRepository {
    override suspend fun persist(
        document: Document,
        version: DocumentVersion,
        provenance: DocumentProvenance,
        fingerprint: SeenFingerprint,
    ) = dao.persist(document.toEntity(), version.toEntity(), provenance.toEntity(), fingerprint.toEntity())

    override suspend fun findById(id: DocumentId): Document? = dao.findById(id.value)?.toDomain()
    override suspend fun findByCanonicalUrl(canonicalUrl: String): Document? = dao.findByCanonicalUrl(canonicalUrl)?.toDomain()
    override suspend fun versions(documentId: DocumentId): List<DocumentVersion> = dao.versions(documentId.value).map { it.toDomain() }
    override suspend fun provenance(documentId: DocumentId): List<DocumentProvenance> = dao.provenance(documentId.value).map { it.toDomain() }
    override suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint? =
        dao.findFingerprint(canonicalUrlHash, sourceId.value)?.toDomain()

    override suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant) =
        dao.updateDisposition(id.value, disposition.name, updatedAt.toEpochMilli())
}
