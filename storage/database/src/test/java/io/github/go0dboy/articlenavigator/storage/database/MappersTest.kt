package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test
    fun sourceRoundTripPreservesSchedulingFields() {
        val source = Source(
            id = SourceId("source-1"),
            name = "Example",
            type = SourceType.RSS,
            url = "https://example.test/feed.xml",
            enabled = true,
            pollPolicy = PollPolicy(Duration.ofHours(2), requiresUnmeteredNetwork = true),
            adapterType = "rss",
            configurationJson = "{\"lang\":\"ru\"}",
            createdAt = Instant.parse("2026-09-15T08:00:00Z"),
            lastSuccessfulCheckAt = Instant.parse("2026-09-15T09:00:00Z"),
            nextCheckAt = Instant.parse("2026-09-15T11:00:00Z"),
        )

        assertEquals(source, source.toEntity().toDomain())
    }

    @Test
    fun documentAndProvenanceRemainIndependent() {
        val document = Document(
            id = DocumentId("doc-1"),
            canonicalUrl = "https://example.test/article",
            title = "Article",
            normalizedText = "Body",
            contentHash = "hash",
            createdAt = Instant.parse("2026-09-15T08:00:00Z"),
            updatedAt = Instant.parse("2026-09-15T08:00:00Z"),
            disposition = ContentDisposition.SAVED,
        )
        val provenance = DocumentProvenance(
            documentId = document.id,
            sourceId = SourceId("source-1"),
            discoveredUrl = "https://example.test/feed-entry",
            resolvedUrl = "https://example.test/article",
            discoveredAt = Instant.parse("2026-09-15T07:55:00Z"),
            fetchedAt = Instant.parse("2026-09-15T08:00:00Z"),
            sourceNameSnapshot = "Example",
            sourceUrlSnapshot = "https://example.test/feed.xml",
            sourceTypeSnapshot = SourceType.RSS.name,
        )

        assertEquals(document, document.toEntity().toDomain())
        assertEquals(provenance, provenance.toEntity().toDomain())
    }
}
