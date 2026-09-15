package io.github.go0dboy.articlenavigator.core.data

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

interface SourceRepository {
    suspend fun upsert(source: Source)
    suspend fun findById(id: SourceId): Source?
    suspend fun findDue(now: Instant): List<Source>
    suspend fun loadCursor(sourceId: SourceId): SourceCursor?
    suspend fun saveCursor(cursor: SourceCursor)
}

interface IngestionRepository {
    suspend fun upsertDiscovered(item: DiscoveredItem)
    suspend fun findDiscoveredById(id: DiscoveredItemId): DiscoveredItem?
    suspend fun findDiscovered(sourceId: SourceId, url: String): DiscoveredItem?
    suspend fun storeRawContent(content: RawContent)
    suspend fun loadRawContent(id: DiscoveredItemId): RawContent?
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
    suspend fun versions(documentId: DocumentId): List<DocumentVersion>
    suspend fun provenance(documentId: DocumentId): List<DocumentProvenance>
    suspend fun findSeen(canonicalUrlHash: String, sourceId: SourceId): SeenFingerprint?
    suspend fun markDisposition(id: DocumentId, disposition: ContentDisposition, updatedAt: Instant)
}
