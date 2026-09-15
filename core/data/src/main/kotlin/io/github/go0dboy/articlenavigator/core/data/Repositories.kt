package io.github.go0dboy.articlenavigator.core.data

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

interface SourceRepository {
    suspend fun upsert(source: Source)
    suspend fun findById(id: SourceId): Source?
    suspend fun findDue(now: Instant): List<Source>
    suspend fun upsertCursor(cursor: SourceCursor)
    suspend fun findCursor(sourceId: SourceId): SourceCursor?
}

interface DiscoveryRepository {
    suspend fun discover(item: DiscoveredItem): DiscoveredItem
    suspend fun upsert(item: DiscoveredItem)
    suspend fun findById(id: DiscoveredItemId): DiscoveredItem?
    suspend fun findBySourceAndUrl(sourceId: SourceId, url: String): DiscoveredItem?
}

interface DocumentRepository {
    suspend fun persist(
        document: Document,
        version: DocumentVersion? = null,
        fingerprint: SeenFingerprint? = null,
    )

    suspend fun findById(id: DocumentId): Document?
    suspend fun findBySourceAndCanonicalUrl(sourceId: SourceId, canonicalUrl: String): Document?
    suspend fun versions(documentId: DocumentId): List<DocumentVersion>
    suspend fun findFingerprint(sourceId: SourceId, canonicalUrlHash: String): SeenFingerprint?
}
