package io.github.go0dboy.articlenavigator.core.model

import java.time.Instant

enum class DiscoveryStatus {
    DISCOVERED,
    FETCHED,
    PROCESSED,
    FAILED,
    SKIPPED,
}

enum class ContentDisposition {
    PENDING,
    REJECTED,
    READ_AND_DISCARDED,
    SAVED,
}

/**
 * A remote item as observed in a source.
 *
 * [url] is the absolute source-published URL before canonicalisation. It is durable provenance.
 * [resolvedUrl] is the final URL after HTTP redirects, when a fetch has happened.
 * [canonicalUrl] is a conservative URL-derived deduplication key, not provenance.
 * [discoveredAt] is the first observation and never changes; [lastSeenAt] advances on rediscovery.
 */
data class DiscoveredItem(
    val id: DiscoveredItemId,
    val sourceId: SourceId,
    val url: String,
    val canonicalUrl: String? = null,
    val resolvedUrl: String? = null,
    val title: String? = null,
    val publishedAt: Instant? = null,
    val discoveredAt: Instant,
    val lastSeenAt: Instant = discoveredAt,
    val contentHash: String? = null,
    val status: DiscoveryStatus = DiscoveryStatus.DISCOVERED,
    val relevanceScore: Double? = null,
    val processingAttempts: Int = 0,
    val nextProcessingAt: Instant? = null,
    val lastProcessingError: String? = null,
)

/**
 * Durable temporary copy of a successful HTTP response.
 *
 * [payload] contains the exact response bytes observed by the fetcher. [contentType] preserves the
 * complete Content-Type header (including charset when present), while [resolvedUrl] preserves the
 * final URL after redirects. This is sufficient to resume extraction after process death without
 * repeating the request while the temporary response remains valid.
 */
data class RawContent(
    val discoveredItemId: DiscoveredItemId,
    val contentType: String?,
    val payload: ByteArray,
    val resolvedUrl: String?,
    val fetchedAt: Instant,
    val httpStatus: Int,
    val expiresAt: Instant?,
)

data class InboxItem(
    val id: InboxItemId,
    val canonicalUrl: String,
    val title: String,
    val publishedAt: Instant? = null,
    val normalizedText: String,
    val contentHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Provenance snapshot captured when a discovery is attached to Inbox. */
data class InboxOrigin(
    val inboxItemId: InboxItemId,
    val discoveredItemId: DiscoveredItemId,
    val sourceId: SourceId,
    val discoveredUrl: String,
    val resolvedUrl: String?,
    val canonicalUrl: String,
    val discoveredAt: Instant,
    val fetchedAt: Instant,
    val sourceNameSnapshot: String,
    val sourceUrlSnapshot: String,
    val sourceTypeSnapshot: String,
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

/** Durable snapshot of where a saved document came from. */
data class DocumentProvenance(
    val documentId: DocumentId,
    val sourceId: SourceId,
    val discoveredUrl: String,
    val resolvedUrl: String?,
    val discoveredAt: Instant,
    val fetchedAt: Instant,
    val sourceNameSnapshot: String,
    val sourceUrlSnapshot: String,
    val sourceTypeSnapshot: String,
    val originKey: String = "${sourceId.value}|$discoveredUrl",
)

data class SeenFingerprint(
    val canonicalUrlHash: String,
    val contentHash: String?,
    val sourceId: SourceId,
    val seenAt: Instant,
    val disposition: ContentDisposition,
)
