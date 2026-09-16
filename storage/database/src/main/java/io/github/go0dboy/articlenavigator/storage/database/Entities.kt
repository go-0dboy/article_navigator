package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "sources",
    indices = [Index("nextCheckAtEpochMillis"), Index("leaseExpiresAtEpochMillis")],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val url: String,
    val enabled: Boolean,
    val pollIntervalSeconds: Long,
    val requiresUnmeteredNetwork: Boolean,
    val adapterType: String,
    val configurationJson: String,
    val createdAtEpochMillis: Long,
    val lastSuccessfulCheckAtEpochMillis: Long?,
    val nextCheckAtEpochMillis: Long?,
    @ColumnInfo(defaultValue = "0") val settingsRevision: Long,
    val leaseToken: String?,
    val leaseExpiresAtEpochMillis: Long?,
)

@Entity(
    tableName = "source_cursors",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class SourceCursorEntity(
    @PrimaryKey val sourceId: String,
    val etag: String?,
    val lastModified: String?,
    val opaqueCursor: String?,
    val lastGuid: String?,
    val lastCheckedAtEpochMillis: Long?,
)

@Entity(
    tableName = "source_collection_states",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
)
data class SourceCollectionStateEntity(
    @PrimaryKey val sourceId: String,
    val consecutiveFailures: Int,
    val lastAttemptAtEpochMillis: Long?,
    val lastErrorType: String?,
    val lastErrorMessage: String?,
    val lastDiscoveredCount: Int,
)

@Entity(
    tableName = "discovered_items",
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("sourceId"),
        Index(value = ["sourceId", "url"], unique = true),
        Index("status"),
        Index("nextProcessingAtEpochMillis"),
        Index("lastSeenAtEpochMillis"),
        Index("processingLeaseExpiresAtEpochMillis"),
    ],
)
data class DiscoveredItemEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    /** Source-published absolute URL. Never rewritten after first observation. */
    val url: String,
    /** Conservative URL-derived deduplication key. */
    val canonicalUrl: String?,
    /** Final URL observed after redirects while fetching. */
    val resolvedUrl: String?,
    val title: String?,
    val publishedAtEpochMillis: Long?,
    /** Time of first observation. */
    val discoveredAtEpochMillis: Long,
    /** Most recent time the source still advertised the item. */
    @ColumnInfo(defaultValue = "0") val lastSeenAtEpochMillis: Long,
    val contentHash: String?,
    val status: String,
    val relevanceScore: Double?,
    @ColumnInfo(defaultValue = "0") val processingAttempts: Int,
    val nextProcessingAtEpochMillis: Long?,
    val lastProcessingError: String?,
    val processingLeaseToken: String?,
    val processingLeaseExpiresAtEpochMillis: Long?,
)

@Entity(
    tableName = "raw_contents",
    foreignKeys = [ForeignKey(entity = DiscoveredItemEntity::class, parentColumns = ["id"], childColumns = ["discoveredItemId"], onDelete = ForeignKey.CASCADE)],
)
data class RawContentEntity(
    @PrimaryKey val discoveredItemId: String,
    val contentType: String?,
    val payload: ByteArray,
    val resolvedUrl: String?,
    val fetchedAtEpochMillis: Long,
    val httpStatus: Int,
    val expiresAtEpochMillis: Long?,
)

@Entity(
    tableName = "inbox_items",
    indices = [
        Index(value = ["canonicalUrl"], unique = true),
        Index("contentHash"),
        Index("createdAtEpochMillis"),
    ],
)
data class InboxItemEntity(
    @PrimaryKey val id: String,
    val canonicalUrl: String,
    val title: String,
    val publishedAtEpochMillis: Long?,
    val normalizedText: String,
    val contentHash: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'default-content-extractor-v1'")
    val parserVersion: String = "default-content-extractor-v1",
    val structuredContentFormat: String? = null,
    val structuredContent: String? = null,
)

@Entity(
    tableName = "inbox_origins",
    primaryKeys = ["inboxItemId", "discoveredItemId"],
    foreignKeys = [
        ForeignKey(entity = InboxItemEntity::class, parentColumns = ["id"], childColumns = ["inboxItemId"], onDelete = ForeignKey.CASCADE),
        // Pending provenance must not disappear because a subscription is hard-deleted.
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["discoveredItemId"], unique = true), Index("sourceId")],
)
data class InboxOriginEntity(
    val inboxItemId: String,
    val discoveredItemId: String,
    val sourceId: String,
    val discoveredUrl: String,
    val resolvedUrl: String?,
    val canonicalUrl: String,
    val discoveredAtEpochMillis: Long,
    val fetchedAtEpochMillis: Long,
    val sourceNameSnapshot: String,
    val sourceUrlSnapshot: String,
    val sourceTypeSnapshot: String,
)

@Entity(tableName = "documents", indices = [Index(value = ["canonicalUrl"], unique = true), Index("contentHash")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val canonicalUrl: String,
    val title: String,
    val author: String?,
    val publishedAtEpochMillis: Long?,
    val language: String?,
    val normalizedText: String,
    val contentHash: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val disposition: String,
    val structuredContentFormat: String? = null,
    val structuredContent: String? = null,
)

@Entity(
    tableName = "document_versions",
    primaryKeys = ["documentId", "version"],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class DocumentVersionEntity(
    val documentId: String,
    val version: Long,
    val contentHash: String,
    val normalizedText: String,
    val fetchedAtEpochMillis: Long,
    val parserVersion: String,
    val structuredContentFormat: String? = null,
    val structuredContent: String? = null,
)

@Entity(
    tableName = "document_provenance",
    primaryKeys = ["documentId", "originKey"],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        // Saved provenance must never disappear because a subscription is removed.
        ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("sourceId")],
)
data class DocumentProvenanceEntity(
    val documentId: String,
    val originKey: String,
    val sourceId: String,
    val discoveredUrl: String,
    val resolvedUrl: String?,
    val discoveredAtEpochMillis: Long,
    val fetchedAtEpochMillis: Long,
    val sourceNameSnapshot: String,
    val sourceUrlSnapshot: String,
    val sourceTypeSnapshot: String,
)

@Entity(
    tableName = "seen_fingerprints",
    primaryKeys = ["canonicalUrlHash", "sourceId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("sourceId")],
)
data class SeenFingerprintEntity(
    val canonicalUrlHash: String,
    val contentHash: String?,
    val sourceId: String,
    val seenAtEpochMillis: Long,
    val disposition: String,
)

@Entity(tableName = "interests")
data class InterestEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val positiveExamplesJson: String,
    val negativeExamplesJson: String,
    val enabled: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
