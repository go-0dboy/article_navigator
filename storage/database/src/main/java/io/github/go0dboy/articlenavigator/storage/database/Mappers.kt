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
    settingsRevision = settingsRevision,
    leaseToken = null,
    leaseExpiresAtEpochMillis = null,
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
    settingsRevision = settingsRevision,
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
    resolvedUrl = resolvedUrl,
    title = title,
    publishedAtEpochMillis = publishedAt?.toEpochMilli(),
    discoveredAtEpochMillis = discoveredAt.toEpochMilli(),
    lastSeenAtEpochMillis = lastSeenAt.toEpochMilli(),
    contentHash = contentHash,
    status = status.name,
    relevanceScore = relevanceScore,
    processingAttempts = processingAttempts,
    nextProcessingAtEpochMillis = nextProcessingAt?.toEpochMilli(),
    lastProcessingError = lastProcessingError,
    processingLeaseToken = null,
    processingLeaseExpiresAtEpochMillis = null,
)

internal fun DiscoveredItemEntity.toDomain() = DiscoveredItem(
    id = DiscoveredItemId(id),
    sourceId = SourceId(sourceId),
    url = url,
    canonicalUrl = canonicalUrl,
    resolvedUrl = resolvedUrl,
    title = title,
    publishedAt = publishedAtEpochMillis?.let(Instant::ofEpochMilli),
    discoveredAt = Instant.ofEpochMilli(discoveredAtEpochMillis),
    lastSeenAt = Instant.ofEpochMilli(if (lastSeenAtEpochMillis == 0L) discoveredAtEpochMillis else lastSeenAtEpochMillis),
    contentHash = contentHash,
    status = DiscoveryStatus.valueOf(status),
    relevanceScore = relevanceScore,
    processingAttempts = processingAttempts,
    nextProcessingAt = nextProcessingAtEpochMillis?.let(Instant::ofEpochMilli),
    lastProcessingError = lastProcessingError,
)

internal fun RawContent.toEntity() = RawContentEntity(
    discoveredItemId = discoveredItemId.value,
    contentType = contentType,
    payload = payload,
    resolvedUrl = resolvedUrl,
    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    httpStatus = httpStatus,
    expiresAtEpochMillis = expiresAt?.toEpochMilli(),
)

internal fun RawContentEntity.toDomain() = RawContent(
    discoveredItemId = DiscoveredItemId(discoveredItemId),
    contentType = contentType,
    payload = payload,
    resolvedUrl = resolvedUrl,
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
    httpStatus = httpStatus,
    expiresAt = expiresAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun InboxItem.toEntity() = InboxItemEntity(
    id = id.value,
    canonicalUrl = canonicalUrl,
    title = title,
    publishedAtEpochMillis = publishedAt?.toEpochMilli(),
    normalizedText = normalizedText,
    contentHash = contentHash,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    parserVersion = parserVersion,
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun InboxItemEntity.toDomain() = InboxItem(
    id = InboxItemId(id),
    canonicalUrl = canonicalUrl,
    title = title,
    publishedAt = publishedAtEpochMillis?.let(Instant::ofEpochMilli),
    normalizedText = normalizedText,
    contentHash = contentHash,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    parserVersion = parserVersion,
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun InboxOrigin.toEntity() = InboxOriginEntity(
    inboxItemId = inboxItemId.value,
    discoveredItemId = discoveredItemId.value,
    sourceId = sourceId.value,
    discoveredUrl = discoveredUrl,
    resolvedUrl = resolvedUrl,
    canonicalUrl = canonicalUrl,
    discoveredAtEpochMillis = discoveredAt.toEpochMilli(),
    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    sourceNameSnapshot = sourceNameSnapshot,
    sourceUrlSnapshot = sourceUrlSnapshot,
    sourceTypeSnapshot = sourceTypeSnapshot,
)

internal fun InboxOriginEntity.toDomain() = InboxOrigin(
    inboxItemId = InboxItemId(inboxItemId),
    discoveredItemId = DiscoveredItemId(discoveredItemId),
    sourceId = SourceId(sourceId),
    discoveredUrl = discoveredUrl,
    resolvedUrl = resolvedUrl,
    canonicalUrl = canonicalUrl,
    discoveredAt = Instant.ofEpochMilli(discoveredAtEpochMillis),
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
    sourceNameSnapshot = sourceNameSnapshot,
    sourceUrlSnapshot = sourceUrlSnapshot,
    sourceTypeSnapshot = sourceTypeSnapshot,
)

internal fun Document.toEntity() = DocumentEntity(
    id = id.value,
    canonicalUrl = canonicalUrl,
    title = title,
    author = author,
    publishedAtEpochMillis = publishedAt?.toEpochMilli(),
    language = language,
    normalizedText = normalizedText,
    contentHash = contentHash,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    disposition = disposition.name,
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun DocumentEntity.toDomain() = Document(
    id = DocumentId(id),
    canonicalUrl = canonicalUrl,
    title = title,
    author = author,
    publishedAt = publishedAtEpochMillis?.let(Instant::ofEpochMilli),
    language = language,
    normalizedText = normalizedText,
    contentHash = contentHash,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    disposition = ContentDisposition.valueOf(disposition),
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun DocumentVersion.toEntity() = DocumentVersionEntity(
    documentId = documentId.value,
    version = version,
    contentHash = contentHash,
    normalizedText = normalizedText,
    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    parserVersion = parserVersion,
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun DocumentVersionEntity.toDomain() = DocumentVersion(
    documentId = DocumentId(documentId),
    version = version,
    contentHash = contentHash,
    normalizedText = normalizedText,
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
    parserVersion = parserVersion,
    structuredContentFormat = structuredContentFormat,
    structuredContent = structuredContent,
)

internal fun DocumentProvenance.toEntity() = DocumentProvenanceEntity(
    documentId = documentId.value,
    originKey = originKey,
    sourceId = sourceId.value,
    discoveredUrl = discoveredUrl,
    resolvedUrl = resolvedUrl,
    discoveredAtEpochMillis = discoveredAt.toEpochMilli(),
    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    sourceNameSnapshot = sourceNameSnapshot,
    sourceUrlSnapshot = sourceUrlSnapshot,
    sourceTypeSnapshot = sourceTypeSnapshot,
)

internal fun DocumentProvenanceEntity.toDomain() = DocumentProvenance(
    documentId = DocumentId(documentId),
    sourceId = SourceId(sourceId),
    discoveredUrl = discoveredUrl,
    resolvedUrl = resolvedUrl,
    discoveredAt = Instant.ofEpochMilli(discoveredAtEpochMillis),
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
    sourceNameSnapshot = sourceNameSnapshot,
    sourceUrlSnapshot = sourceUrlSnapshot,
    sourceTypeSnapshot = sourceTypeSnapshot,
    originKey = originKey,
)

internal fun SeenFingerprint.toEntity() = SeenFingerprintEntity(
    canonicalUrlHash, contentHash, sourceId.value, seenAt.toEpochMilli(), disposition.name,
)

internal fun SeenFingerprintEntity.toDomain() = SeenFingerprint(
    canonicalUrlHash, contentHash, SourceId(sourceId), Instant.ofEpochMilli(seenAtEpochMillis), ContentDisposition.valueOf(disposition),
)
