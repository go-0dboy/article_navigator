package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.collector.api.UrlCanonicalizer
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.KnowledgeRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlinx.coroutines.CancellationException

fun interface SourceAdapterResolver {
    fun resolve(source: Source): SourceAdapter?
}

data class IngestionReport(
    val processed: Int,
    val addedToInbox: Int,
    val mergedIntoInbox: Int,
    val alreadyKnown: Int,
    val failed: Int,
    val skipped: Int,
)

class IngestionPipeline(
    private val sourceRepository: SourceRepository,
    private val ingestionRepository: IngestionRepository,
    private val inboxRepository: InboxRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val adapterResolver: SourceAdapterResolver,
    private val extractor: ContentExtractor = DefaultContentExtractor(),
    private val clock: Clock = Clock.systemUTC(),
    private val retryBaseDelay: Duration = Duration.ofMinutes(15),
    private val retryMaxDelay: Duration = Duration.ofHours(24),
) {
    suspend fun processReady(limit: Int = 20): IngestionReport {
        require(limit > 0)
        val now = clock.instant()
        val ready = ingestionRepository.findReadyForProcessing(now, limit)
        var added = 0
        var merged = 0
        var known = 0
        var failed = 0
        var skipped = 0

        for (item in ready) {
            when (processOne(item, now)) {
                Outcome.ADDED -> added++
                Outcome.MERGED -> merged++
                Outcome.KNOWN -> known++
                Outcome.FAILED -> failed++
                Outcome.SKIPPED -> skipped++
            }
        }
        return IngestionReport(ready.size, added, merged, known, failed, skipped)
    }

    private suspend fun processOne(item: DiscoveredItem, now: Instant): Outcome {
        val canonicalUrl = item.canonicalUrl
            ?: UrlCanonicalizer.canonicalize(item.url)
            ?: return skip(item, "Invalid article URL: ${item.url}")
        val canonicalHash = sha256(canonicalUrl)

        val previousDisposition = knowledgeRepository.findSeen(canonicalHash, item.sourceId)
        if (previousDisposition != null) {
            ingestionRepository.upsertDiscovered(
                item.copy(
                    canonicalUrl = canonicalUrl,
                    status = DiscoveryStatus.PROCESSED,
                    nextProcessingAt = null,
                    lastProcessingError = null,
                ),
            )
            ingestionRepository.deleteRawContent(item.id)
            return Outcome.KNOWN
        }

        val source = sourceRepository.findById(item.sourceId)
            ?: return skip(item, "Source ${item.sourceId.value} no longer exists")
        val adapter = adapterResolver.resolve(source)
            ?: return fail(item, now, IllegalStateException("No adapter ${source.adapterType} for ${source.type}"))

        return try {
            val fetched = adapter.fetch(item)
            if (fetched.statusCode !in 200..299) {
                val error = IllegalStateException("Article request returned HTTP ${fetched.statusCode}")
                return if (isRetryableHttpStatus(fetched.statusCode)) {
                    fail(item, now, error)
                } else {
                    skip(item, error.message ?: "HTTP ${fetched.statusCode}")
                }
            }

            val fetchedAt = clock.instant()
            ingestionRepository.storeRawContent(
                RawContent(
                    discoveredItemId = item.id,
                    mimeType = fetched.contentType,
                    payload = fetched.body.toString(Charsets.UTF_8),
                    fetchedAt = fetchedAt,
                    httpStatus = fetched.statusCode,
                    expiresAt = fetchedAt.plus(Duration.ofHours(24)),
                ),
            )

            val extractionBaseUrl = fetched.resolvedUrl ?: canonicalUrl
            val extracted = try {
                extractor.extract(fetched.body, fetched.contentType, extractionBaseUrl)
            } catch (error: UnsupportedContentTypeException) {
                return skip(item, error.message ?: "Unsupported content type")
            } catch (error: IllegalArgumentException) {
                return skip(item, error.message ?: "Content cannot be extracted")
            }

            val contentHash = sha256(extracted.normalizedText)
            val fetchedItem = item.copy(
                canonicalUrl = canonicalUrl,
                resolvedUrl = fetched.resolvedUrl ?: item.resolvedUrl,
                contentHash = contentHash,
                status = DiscoveryStatus.FETCHED,
                nextProcessingAt = null,
                lastProcessingError = null,
            )
            ingestionRepository.upsertDiscovered(fetchedItem)

            val title = extracted.title?.takeIf { it.isNotBlank() }
                ?: item.title?.takeIf { it.isNotBlank() }
                ?: canonicalUrl

            // Explicit policy: merge only on exact canonical URL or exact normalized-text SHA-256.
            val existingDocument = knowledgeRepository.findByCanonicalUrl(canonicalUrl)
                ?: knowledgeRepository.findByContentHash(contentHash)
            if (existingDocument != null) {
                knowledgeRepository.recordDiscovery(
                    documentId = existingDocument.id,
                    provenance = DocumentProvenance(
                        documentId = existingDocument.id,
                        sourceId = item.sourceId,
                        discoveredUrl = item.url,
                        resolvedUrl = fetched.resolvedUrl,
                        discoveredAt = item.discoveredAt,
                        fetchedAt = fetchedAt,
                        sourceNameSnapshot = source.name,
                        sourceUrlSnapshot = source.url,
                        sourceTypeSnapshot = source.type.name,
                    ),
                    fingerprint = SeenFingerprint(
                        canonicalUrlHash = canonicalHash,
                        contentHash = contentHash,
                        sourceId = item.sourceId,
                        seenAt = now,
                        disposition = ContentDisposition.SAVED,
                    ),
                    discoveredItemId = item.id,
                )
                return Outcome.KNOWN
            }

            val existingInbox = inboxRepository.findByCanonicalUrl(canonicalUrl)
                ?: inboxRepository.findByContentHash(contentHash)
            if (existingInbox != null) {
                inboxRepository.attachOrigin(
                    existingInbox.id,
                    InboxOrigin(
                        inboxItemId = existingInbox.id,
                        discoveredItemId = item.id,
                        sourceId = item.sourceId,
                        discoveredUrl = item.url,
                        resolvedUrl = fetched.resolvedUrl,
                        canonicalUrl = canonicalUrl,
                        discoveredAt = item.discoveredAt,
                        fetchedAt = fetchedAt,
                    ),
                )
                return Outcome.MERGED
            }

            val inboxId = InboxItemId("inbox-${sha256(canonicalUrl)}")
            inboxRepository.put(
                InboxItem(
                    id = inboxId,
                    canonicalUrl = canonicalUrl,
                    title = title,
                    publishedAt = item.publishedAt,
                    normalizedText = extracted.normalizedText,
                    contentHash = contentHash,
                    createdAt = now,
                    updatedAt = now,
                ),
                InboxOrigin(
                    inboxItemId = inboxId,
                    discoveredItemId = item.id,
                    sourceId = item.sourceId,
                    discoveredUrl = item.url,
                    resolvedUrl = fetched.resolvedUrl,
                    canonicalUrl = canonicalUrl,
                    discoveredAt = item.discoveredAt,
                    fetchedAt = fetchedAt,
                ),
            )
            Outcome.ADDED
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(item, now, error)
        }
    }

    private suspend fun fail(item: DiscoveredItem, now: Instant, error: Exception): Outcome {
        val attempts = item.processingAttempts + 1
        val multiplier = 1L shl min(attempts - 1, 10)
        val delay = retryBaseDelay.multipliedBy(multiplier).coerceAtMost(retryMaxDelay)
        ingestionRepository.upsertDiscovered(
            item.copy(
                status = DiscoveryStatus.FAILED,
                processingAttempts = attempts,
                nextProcessingAt = now.plus(delay),
                lastProcessingError = errorDescription(error),
            ),
        )
        return Outcome.FAILED
    }

    private suspend fun skip(item: DiscoveredItem, reason: String): Outcome {
        ingestionRepository.upsertDiscovered(
            item.copy(
                status = DiscoveryStatus.SKIPPED,
                nextProcessingAt = null,
                lastProcessingError = reason.take(500),
            ),
        )
        ingestionRepository.deleteRawContent(item.id)
        return Outcome.SKIPPED
    }

    private fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599

    private fun errorDescription(error: Exception): String =
        (error.message ?: error::class.java.simpleName).take(500)

    private fun Duration.coerceAtMost(other: Duration): Duration = if (this > other) other else this

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private enum class Outcome { ADDED, MERGED, KNOWN, FAILED, SKIPPED }
}
