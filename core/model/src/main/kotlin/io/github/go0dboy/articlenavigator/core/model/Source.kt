package io.github.go0dboy.articlenavigator.core.model

import java.time.Duration
import java.time.Instant

enum class SourceType {
    RSS,
    ATOM,
    REST_API,
    HTML_PAGE,
    SITE_ADAPTER,
}

data class PollPolicy(
    val interval: Duration,
    val requiresUnmeteredNetwork: Boolean = false,
)

data class Source(
    val id: SourceId,
    val name: String,
    val type: SourceType,
    val url: String,
    val enabled: Boolean,
    val pollPolicy: PollPolicy,
    val adapterType: String,
    val configurationJson: String = "{}",
    val createdAt: Instant,
    val lastSuccessfulCheckAt: Instant? = null,
    val nextCheckAt: Instant? = null,
)

data class SourceCursor(
    val sourceId: SourceId,
    val etag: String? = null,
    val lastModified: String? = null,
    val opaqueCursor: String? = null,
    val lastGuid: String? = null,
    val lastCheckedAt: Instant? = null,
)
