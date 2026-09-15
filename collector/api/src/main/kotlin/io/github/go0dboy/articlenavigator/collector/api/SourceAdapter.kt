package io.github.go0dboy.articlenavigator.collector.api

import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceType

interface SourceAdapter {
    val supportedType: SourceType

    suspend fun discover(
        source: Source,
        cursor: SourceCursor?,
    ): DiscoveryResult

    suspend fun fetch(item: DiscoveredItem): FetchResult
}

data class DiscoveryResult(
    val items: List<DiscoveredItem>,
    val nextCursor: SourceCursor,
)

data class FetchResult(
    val item: DiscoveredItem,
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    val fetchedHeaders: Map<String, String> = emptyMap(),
)
