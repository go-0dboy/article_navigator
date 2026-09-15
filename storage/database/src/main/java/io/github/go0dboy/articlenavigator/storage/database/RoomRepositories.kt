package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.data.DiscoveryRepository
import io.github.go0dboy.articlenavigator.core.data.DocumentRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
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

    override suspend fun findDue(now: Instant): List<Source> =
        dao.findDue(now.toEpochMilli()).map(SourceEntity::toDomain)

    override suspend fun upsertCursor(cursor: SourceCursor) = dao.upsertCursor(cursor.toEntity())

    override suspend fun findCursor(sourceId: SourceId): SourceCursor? =
        dao.findCursor(sourceId.value)?.toDomain()
}

class RoomDiscoveryRepository(
    private val dao: DiscoveryDao,
) : DiscoveryRepository {
    override suspend fun discover(item: DiscoveredItem): DiscoveredItem {
        dao.insertIfAbsent(item.toEntity())
        return requireNotNull(dao.findBySourceAndUrl(item.sourceId.value, item.url)).toDomain()
    }

    override suspend fun upsert(item: DiscoveredItem) = dao.upsert(item.toEntity())

    override suspend fun findById(id: DiscoveredItemId): DiscoveredItem? =
        dao.findById(id.value)?.toDomain()

    override suspend fun findBySourceAndUrl(sourceId: SourceId, url: String): DiscoveredItem? =
        dao.findBySourceAndUrl(sourceId.value, url)?.toDomain()
}

class RoomDocumentRepository(
    private val dao: DocumentDao,
) : DocumentRepository {
    override suspend fun persist(
        document: Document,
        version: DocumentVersion?,
        fingerprint: SeenFingerprint?,
    ) {
        require(version == null || version.documentId == document.id) {
            "DocumentVersion must belong to the persisted document"
        }
        require(fingerprint == null || fingerprint.sourceId == document.sourceId) {
            "SeenFingerprint must belong to the persisted document source"
        }
        dao.persist(
            document = document.toEntity(),
            version = version?.toEntity(),
            fingerprint = fingerprint?.toEntity(),
        )
    }

    override suspend fun findById(id: DocumentId): Document? = dao.findById(id.value)?.toDomain()

    override suspend fun findBySourceAndCanonicalUrl(
        sourceId: SourceId,
        canonicalUrl: String,
    ): Document? = dao.findBySourceAndCanonicalUrl(sourceId.value, canonicalUrl)?.toDomain()

    override suspend fun versions(documentId: DocumentId): List<DocumentVersion> =
        dao.versions(documentId.value).map(DocumentVersionEntity::toDomain)

    override suspend fun findFingerprint(
        sourceId: SourceId,
        canonicalUrlHash: String,
    ): SeenFingerprint? = dao.findFingerprint(sourceId.value, canonicalUrlHash)?.toDomain()
}
