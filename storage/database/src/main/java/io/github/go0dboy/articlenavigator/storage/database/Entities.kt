package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "sources")
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
)

@Entity(tableName = "source_cursors")
data class SourceCursorEntity(
    @PrimaryKey val sourceId: String,
    val etag: String?,
    val lastModified: String?,
    val opaqueCursor: String?,
    val lastGuid: String?,
    val lastCheckedAtEpochMillis: Long?,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
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
)

@Entity(
    tableName = "document_versions",
    primaryKeys = ["documentId", "version"],
)
data class DocumentVersionEntity(
    val documentId: String,
    val version: Long,
    val contentHash: String,
    val normalizedText: String,
    val fetchedAtEpochMillis: Long,
    val parserVersion: String,
)

@Entity(
    tableName = "seen_fingerprints",
    primaryKeys = ["canonicalUrlHash", "sourceId"],
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
