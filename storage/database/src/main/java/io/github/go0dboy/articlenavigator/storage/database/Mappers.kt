package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
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
    pollPolicy = PollPolicy(
        interval = Duration.ofSeconds(pollIntervalSeconds),
        requiresUnmeteredNetwork = requiresUnmeteredNetwork,
    ),
    adapterType = adapterType,
    configurationJson = configurationJson,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    lastSuccessfulCheckAt = lastSuccessfulCheckAtEpochMillis?.let(Instant::ofEpochMilli),
    nextCheckAt = nextCheckAtEpochMillis?.let(Instant::ofEpochMilli),
)

internal fun SourceCursor.toEntity() = SourceCursorEntity(
    sourceId = sourceId.value,
    etag = etag,
    lastModified = lastModified,
    opaqueCursor = opaqueCursor,
    lastGuid = lastGuid,
    lastCheckedAtEpochMillis = lastCheckedAt?.toEpochMilli(),
)

internal fun SourceCursorEntity.toDomain() = SourceCursor(
    sourceId = SourceId(sourceId),
    etag = etag,
    lastModified = lastModified,
    opaqueCursor = opaqueCursor,
    lastGuid = lastGuid,
    lastCheckedAt = lastCheckedAtEpochMillis?.let(Instant::ofEpochMilli),
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
)

internal fun Document.toEntity() = DocumentEntity(
    id = id.value,
    sourceId = sourceId.value,
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
)

internal fun DocumentEntity.toDomain() = Document(
    id = DocumentId(id),
    sourceId = SourceId(sourceId),
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
)

internal fun DocumentVersion.toEntity() = DocumentVersionEntity(
    documentId = documentId.value,
    version = version,
    contentHash = contentHash,
    normalizedText = normalizedText,
    fetchedAtEpochMillis = fetchedAt.toEpochMilli(),
    parserVersion = parserVersion,
)

internal fun DocumentVersionEntity.toDomain() = DocumentVersion(
    documentId = DocumentId(documentId),
    version = version,
    contentHash = contentHash,
    normalizedText = normalizedText,
    fetchedAt = Instant.ofEpochMilli(fetchedAtEpochMillis),
    parserVersion = parserVersion,
)

internal fun SeenFingerprint.toEntity() = SeenFingerprintEntity(
    canonicalUrlHash = canonicalUrlHash,
    contentHash = contentHash,
    sourceId = sourceId.value,
    seenAtEpochMillis = seenAt.toEpochMilli(),
    disposition = disposition.name,
)

internal fun SeenFingerprintEntity.toDomain() = SeenFingerprint(
    canonicalUrlHash = canonicalUrlHash,
    contentHash = contentHash,
    sourceId = SourceId(sourceId),
    seenAt = Instant.ofEpochMilli(seenAtEpochMillis),
    disposition = ContentDisposition.valueOf(disposition),
)
