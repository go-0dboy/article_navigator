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
    suspend fun upsert(source: Source)
    suspend fun findById(id: SourceId): Source?
    suspend fun findDue(now: Instant): List<Source>
    suspend fun listAll(): List<Source>
    suspend fun loadCursor(sourceId: SourceId): SourceCursor?
    suspend fun saveCursor(cursor: SourceCursor)
}

interface CollectionStateRepository {
    suspend fun load(sourceId: SourceId): SourceCollectionState?
    suspend fun save(state: SourceCollectionState)
}

interface IngestionRepository {
    suspend fun upsertDiscovered(item: DiscoveredItem)
    suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem?
    suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem?
    suspend fun findReadyForProcessing(now: Instant, limit: Int): List<DiscoveredItem>
    suspend fun storeRawContent(content: RawContent)
    suspend fun loadRawContent(id: DiscoveredItemId): RawContent?
    suspend fun deleteRawContent(id: DiscoveredItemId)
}

interface InboxRepository {
    suspend fun put(item: InboxItem, origin: InboxOrigin)
    suspend fun attachOrigin(itemId: InboxItemId, origin: InboxOrigin)
    suspend fun listPending(limit: Int = 100): List<InboxItem>
    suspend fun findById(id: InboxItemId): InboxItem?
    suspend fun findByCanonicalUrl(canonicalUrl: String): InboxItem?
    suspend fun findByContentHash(contentHash: String): InboxItem?
    suspend fun origins(id: InboxItemId): List<InboxOrigin>

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
