package io.github.go0dboy.articlenavigator.core.model

import java.util.UUID

@JvmInline
value class SourceId(val value: String) {
    companion object {
        fun new(): SourceId = SourceId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class DiscoveredItemId(val value: String) {
    companion object {
        fun new(): DiscoveredItemId = DiscoveredItemId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class DocumentId(val value: String) {
    companion object {
        fun new(): DocumentId = DocumentId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class InterestId(val value: String) {
    companion object {
        fun new(): InterestId = InterestId(UUID.randomUUID().toString())
    }
}
