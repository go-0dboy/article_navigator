package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import java.security.MessageDigest
import java.time.Clock

class InboxService(
    private val repository: InboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(limit: Int = 100): List<InboxItem> = repository.listPending(limit)

    suspend fun get(id: InboxItemId): InboxItem? = repository.findById(id)

    suspend fun reject(id: InboxItemId) = discard(id, ContentDisposition.REJECTED)

    suspend fun readAndDiscard(id: InboxItemId) = discard(id, ContentDisposition.READ_AND_DISCARDED)

    suspend fun save(id: InboxItemId): DocumentId {
        val item = repository.findById(id) ?: throw NoSuchElementException("Inbox item ${id.value} not found")
        val origins = repository.origins(id)
        require(origins.isNotEmpty()) { "Inbox item ${id.value} has no provenance" }
        val now = clock.instant()
        val documentId = DocumentId.new()
        val fetchedAt = origins.maxOf { it.fetchedAt }

        val document = Document(
            id = documentId,
            canonicalUrl = item.canonicalUrl,
            title = item.title,
            publishedAt = item.publishedAt,
            normalizedText = item.normalizedText,
            contentHash = item.contentHash,
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val version = DocumentVersion(
            documentId = documentId,
            version = 1,
            contentHash = item.contentHash,
            normalizedText = item.normalizedText,
            fetchedAt = fetchedAt,
            parserVersion = PARSER_VERSION,
        )
        val provenances = origins.map { origin ->
            DocumentProvenance(
                documentId = documentId,
                sourceId = origin.sourceId,
                discoveredUrl = origin.discoveredUrl,
                resolvedUrl = origin.resolvedUrl,
                discoveredAt = origin.discoveredAt,
                fetchedAt = origin.fetchedAt,
                sourceNameSnapshot = origin.sourceNameSnapshot,
                sourceUrlSnapshot = origin.sourceUrlSnapshot,
                sourceTypeSnapshot = origin.sourceTypeSnapshot,
            )
        }
        val fingerprints = origins
            .map { origin ->
                SeenFingerprint(
                    canonicalUrlHash = sha256(origin.canonicalUrl),
                    contentHash = item.contentHash,
                    sourceId = origin.sourceId,
                    seenAt = now,
                    disposition = ContentDisposition.SAVED,
                )
            }
            .distinctBy { it.canonicalUrlHash to it.sourceId }

        repository.save(id, document, version, provenances, fingerprints)
        return documentId
    }

    private suspend fun discard(id: InboxItemId, disposition: ContentDisposition) {
        require(disposition == ContentDisposition.REJECTED || disposition == ContentDisposition.READ_AND_DISCARDED)
        val item = repository.findById(id) ?: return
        val now = clock.instant()
        val fingerprints = repository.origins(id)
            .map { origin ->
                SeenFingerprint(
                    canonicalUrlHash = sha256(origin.canonicalUrl),
                    contentHash = item.contentHash,
                    sourceId = origin.sourceId,
                    seenAt = now,
                    disposition = disposition,
                )
            }
            .distinctBy { it.canonicalUrlHash to it.sourceId }
        repository.discard(id, disposition, fingerprints)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val PARSER_VERSION: String = "default-content-extractor-v1"
    }
}
