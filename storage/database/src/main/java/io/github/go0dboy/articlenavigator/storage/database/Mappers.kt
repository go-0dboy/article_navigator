package io.github.go0dboy.articlenavigator.storage.database

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
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCollectionState
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant

internal fun Source.toEntity() = SourceEntity(
    id = id.value,
    name = name,
    type = type.name,
    url = url,
    enabled = enabled,
    pollIntervalSeconds = pollPolicy.interval.seconds,
    requiresUnmeteredNetwork = pollPolicy.requiresUnmeteredNetwork,
    adapterType = adapterType,
    configurationJson = configurationJson,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    lastSuccessfulCheckAtEpochMillis = lastSuccessfulCheckAt?.toEpochMilli(),
    nextCheckAtEpochMillis = nextCheckAt?.toEpochMilli(),
)

internal fun SourceEntity.toDomain() = Source(
    id = SourceId(id),
    name = name,
    type = SourceType.valueOf(type),
    url = url,
    enabled = enabled,
    pollPolicy = PollPolicy(Duration.ofSeconds(pollIntervalSeconds), requiresUnmeteredNetwork),
    adapterType = adapterType,
    configurationJson = configurationJson,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    lastSuccessfulCheckAt = lastSuccessfulCheckAtEpochMillis?.let(Instant::ofEpochMilli),
    nextCheckAt = nextCheckAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun SourceCursor.toEntity() = SourceCursorEntity(
    sourceId.value, etag, lastModified, opaqueCursor, lastGuid, lastCheckedAt?.toEpochMilli(),
)

internal fun SourceCursorEntity.toDomain() = SourceCursor(
    SourceId(sourceId), etag, lastModified, opaqueCursor, lastGuid, lastCheckedAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun SourceCollectionState.toEntity() = SourceCollectionStateEntity(
    sourceId.value,
    consecutiveFailures,
    lastAttemptAt?.toEpochMilli(),
    lastErrorType,
    lastErrorMessage,
    lastDiscoveredCount,
)

internal fun SourceCollectionStateEntity.toDomain() = SourceCollectionState(
    SourceId(sourceId),
    consecutiveFailures,
    lastAttemptAtEpochMillis?.let(Instant::ofEpochMilli),
    lastErrorType,
    lastErrorMessage,
    lastDiscoveredCount,
)

internal fun DiscoveredItem.toEntity() = DiscoveredItemEntity(
    id = id.value,
    sourceId = sourceId.value,
    url = url,
    canonicalUrl = canonicalUrl,
    title = title,
    publishedAtEpochMillis = publishedAt?.toEpochMilli(),
    discoveredAtEpochMillis = discoveredAt.toEpochMilli(),
    contentHash = contentHash,
    status = status.name,
    relevanceScore = relevanceScore,
    processingAttempts = processingAttempts,
    nextProcessingAtEpochMillis = nextProcessingAt?.toEpochMilli(),
    lastProcessingError = lastProcessingError,
)

internal fun DiscoveredItemEntity.toDomain() = DiscoveredItem(
    id = DiscoveredItemId(id),
    sourceId = SourceId(sourceId),
    url = url,
    canonicalUrl = canonicalUrl,
    title = title,
    publishedAt = publishedAtEpochMillis?.let(Instant::ofEpochMilli),
    discoveredAt = Instant.ofEpochMilli(discoveredAtEpochMillis),
    contentHash = contentHash,
    status = DiscoveryStatus.valueOf(status),
    relevanceScore = relevanceScore,
    processingAttempts = processingAttempts,
    nextProcessingAt = nextProcessingAtEpochMillis?.let(Instant::ofEpochMilli),
    lastProcessingError = lastProcessingError,
)

internal fun RawContent.toEntity() = RawContentEntity(
    discoveredItemId.value, mimeType, payload, fetchedAt.toEpochMilli(), httpStatus, expiresAt?.toEpochMilli(),
)

internal fun RawContentEntity.toDomain() = RawContent(
    DiscoveredItemId(discoveredItemId), mimeType, payload, Instant.ofEpochMilli(fetchedAtEpochMillis), httpStatus,
    expiresAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun InboxItem.toEntity() = InboxItemEntity(
    id.value,
    canonicalUrl,
    title,
    publishedAt?.toEpochMilli(),
    normalizedText,
    contentHash,
    createdAt.toEpochMilli(),
    updatedAt.toEpochMilli(),
)

internal fun InboxItemEntity.toDomain() = InboxItem(
    InboxItemId(id),
    canonicalUrl,
    title,
    publishedAtEpochMillis?.let(Instant::ofEpochMilli),
    normalizedText,
    contentHash,
    Instant.ofEpochMilli(createdAtEpochMillis),
    Instant.ofEpochMilli(updatedAtEpochMillis),
)

internal fun InboxOrigin.toEntity() = InboxOriginEntity(
    inboxItemId.value,
    discoveredItemId.value,
    sourceId.value,
    discoveredUrl,
    canonicalUrl,
    discoveredAt.toEpochMilli(),
    fetchedAt.toEpochMilli(),
)

internal fun InboxOriginEntity.toDomain() = InboxOrigin(
    InboxItemId(inboxItemId),
    DiscoveredItemId(discoveredItemId),
    SourceId(sourceId),
    discoveredUrl,
    canonicalUrl,
    Instant.ofEpochMilli(discoveredAtEpochMillis),
    Instant.ofEpochMilli(fetchedAtEpochMillis),
)

internal fun Document.toEntity() = DocumentEntity(
    id.value, canonicalUrl, title, author, publishedAt?.toEpochMilli(), language, normalizedText, contentHash,
    createdAt.toEpochMilli(), updatedAt.toEpochMilli(), disposition.name,
)

internal fun DocumentEntity.toDomain() = Document(
    DocumentId(id), canonicalUrl, title, author, publishedAtEpochMillis?.let(Instant::ofEpochMilli), language, normalizedText,
    contentHash, Instant.ofEpochMilli(createdAtEpochMillis), Instant.ofEpochMilli(updatedAtEpochMillis), ContentDisposition.valueOf(disposition),
)

internal fun DocumentVersion.toEntity() = DocumentVersionEntity(
    documentId.value, version, contentHash, normalizedText, fetchedAt.toEpochMilli(), parserVersion,
)

internal fun DocumentVersionEntity.toDomain() = DocumentVersion(
    DocumentId(documentId), version, contentHash, normalizedText, Instant.ofEpochMilli(fetchedAtEpochMillis), parserVersion,
)

internal fun DocumentProvenance.toEntity() = DocumentProvenanceEntity(
    documentId.value, originKey, sourceId.value, discoveredUrl, discoveredAt.toEpochMilli(), fetchedAt.toEpochMilli(),
)

internal fun DocumentProvenanceEntity.toDomain() = DocumentProvenance(
    DocumentId(documentId), SourceId(sourceId), discoveredUrl, Instant.ofEpochMilli(discoveredAtEpochMillis),
    Instant.ofEpochMilli(fetchedAtEpochMillis), originKey,
)

internal fun SeenFingerprint.toEntity() = SeenFingerprintEntity(
    canonicalUrlHash, contentHash, sourceId.value, seenAt.toEpochMilli(), disposition.name,
)

internal fun SeenFingerprintEntity.toDomain() = SeenFingerprint(
    canonicalUrlHash, contentHash, SourceId(sourceId), Instant.ofEpochMilli(seenAtEpochMillis), ContentDisposition.valueOf(disposition),
)
