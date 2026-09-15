package io.github.go0dboy.articlenavigator.core.model

import java.time.Instant

enum class DiscoveryStatus {
    DISCOVERED,
    FETCHED,
    PROCESSED,
    FAILED,
}

enum class ContentDisposition {
    PENDING,
    REJECTED,
    READ_AND_DISCARDED,
    SAVED,
}

data class DiscoveredItem(
    val id: DiscoveredItemId,
    val sourceId: SourceId,
    val url: String,
    val canonicalUrl: String? = null,
    val title: String? = null,
    val publishedAt: Instant? = null,
    val discoveredAt: Instant,
    val contentHash: String? = null,
    val status: DiscoveryStatus = DiscoveryStatus.DISCOVERED,
    val relevanceScore: Double? = null,
)

data class RawContent(
    val discoveredItemId: DiscoveredItemId,
    val mimeType: String?,
    val payload: String,
    val fetchedAt: Instant,
    val httpStatus: Int,
    val expiresAt: Instant?,
)

data class Document(
    val id: DocumentId,
    val canonicalUrl: String,
    val title: String,
    val author: String? = null,
    val publishedAt: Instant? = null,
    val language: String? = null,
    val normalizedText: String,
    val contentHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val disposition: ContentDisposition,
)

data class DocumentVersion(
    val documentId: DocumentId,
    val version: Long,
    val contentHash: String,
    val normalizedText: String,
    val fetchedAt: Instant,
    val parserVersion: String,
)

data class DocumentProvenance(
    val documentId: DocumentId,
    val sourceId: SourceId,
    val discoveredUrl: String,
    val discoveredAt: Instant,
    val fetchedAt: Instant,
)

data class SeenFingerprint(
    val canonicalUrlHash: String,
    val contentHash: String?,
    val sourceId: SourceId,
    val seenAt: Instant,
    val disposition: ContentDisposition,
)
