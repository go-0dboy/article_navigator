package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.collector.api.UrlCanonicalizer
import io.github.go0dboy.articlenavigator.core.data.ArticleProcessingLease
import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.KnowledgeRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.Source
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

fun interface SourceAdapterResolver {
    fun resolve(source: Source): SourceAdapter?
}

data class IngestionReport(
    /** Number of processing leases acquired during this pass. */
    val processed: Int,
    val addedToInbox: Int,
    val mergedIntoInbox: Int,
    val alreadyKnown: Int,
    val failed: Int,
    val skipped: Int,
    val stale: Int = 0,
    val budgetExhausted: Boolean = false,
)

class IngestionPipeline(
    private val sourceRepository: SourceRepository,
    private val ingestionRepository: IngestionRepository,
    // Kept in the constructor for source compatibility with Phase 4 callers/tests. Runtime finalisation
    // is deliberately routed only through IngestionRepository's atomic ownership boundary.
    @Suppress("unused") private val inboxRepository: InboxRepository,
    @Suppress("unused") private val knowledgeRepository: KnowledgeRepository,
    private val adapterResolver: SourceAdapterResolver,
    private val extractor: ContentExtractor = DefaultContentExtractor(),
    private val clock: Clock = Clock.systemUTC(),
    private val retryBaseDelay: Duration = Duration.ofMinutes(15),
    private val retryMaxDelay: Duration = Duration.ofHours(24),
    private val processingLeaseDuration: Duration = Duration.ofMinutes(10),
    private val maxRunDuration: Duration = Duration.ofMinutes(8),
    private val rawRetention: Duration = Duration.ofHours(24),
) {
    suspend fun processReady(
        limit: Int = 20,
        isUnmeteredNetwork: Boolean = true,
    ): IngestionReport {
        require(limit > 0)
        val runStartedAt = clock.instant()
        var claimed = 0
        var added = 0
        var merged = 0
        var known = 0
        var failed = 0
        var skipped = 0
        var stale = 0
        var budgetExhausted = false

        while (claimed < limit) {
            val now = clock.instant()
            if (Duration.between(runStartedAt, now) >= maxRunDuration) {
                budgetExhausted = true
                break
            }
            val token = UUID.randomUUID().toString()
            val lease = ingestionRepository.tryClaimNext(
                runToken = token,
                now = now,
                leaseExpiresAt = now.plus(processingLeaseDuration),
                isUnmeteredNetwork = isUnmeteredNetwork,
            ) ?: break
            claimed++

            val outcome = try {
                processOne(lease)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    runCatching { ingestionRepository.releaseProcessing(lease) }
                }
                throw error
            }
            when (outcome) {
                Outcome.ADDED -> added++
                Outcome.MERGED -> merged++
                Outcome.KNOWN -> known++
                Outcome.FAILED -> failed++
                Outcome.SKIPPED -> skipped++
                Outcome.STALE -> stale++
            }
        }
        return IngestionReport(claimed, added, merged, known, failed, skipped, stale, budgetExhausted)
    }

    private suspend fun processOne(lease: ArticleProcessingLease): Outcome {
        val item = lease.item
        val canonicalUrl = item.canonicalUrl
            ?: UrlCanonicalizer.canonicalize(item.url)
            ?: return skip(lease, null, "Invalid article URL: ${item.url}")
        val canonicalHash = sha256(canonicalUrl)

        val source = sourceRepository.findById(item.sourceId)
            ?: throw IllegalStateException("Claimed discovery references missing Source ${item.sourceId.value}")

        val reusableRaw = ingestionRepository.loadRawContent(lease)
            ?.takeIf { raw ->
                raw.httpStatus in 200..299 &&
                    raw.expiresAt?.let { it > clock.instant() } != false
            }

        val response = if (reusableRaw != null) {
            DurableResponse(
                body = reusableRaw.payload,
                contentType = reusableRaw.contentType,
                resolvedUrl = reusableRaw.resolvedUrl,
                fetchedAt = reusableRaw.fetchedAt,
            )
        } else {
            val adapter = adapterResolver.resolve(source)
                ?: throw IllegalStateException("No adapter ${source.adapterType} for ${source.type}")
            val fetched = try {
                adapter.fetch(item)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return fail(lease, clock.instant(), error)
            }
            val responseAt = clock.instant()
            if (fetched.statusCode !in 200..299) {
                val error = ArticleHttpException(fetched.statusCode)
                return if (isRetryableHttpStatus(fetched.statusCode)) {
                    fail(
                        lease = lease,
                        failureAt = responseAt,
                        error = error,
                        minimumDelay = parseRetryAfter(fetched.retryAfterHeader(), responseAt),
                    )
                } else {
                    skip(lease, canonicalUrl, error.message ?: "HTTP ${fetched.statusCode}")
                }
            }

            val raw = RawContent(
                discoveredItemId = item.id,
                contentType = fetched.contentType,
                payload = fetched.body,
                resolvedUrl = fetched.resolvedUrl,
                fetchedAt = responseAt,
                httpStatus = fetched.statusCode,
                expiresAt = responseAt.plus(rawRetention),
            )
            if (!ingestionRepository.storeRawContent(lease, raw, responseAt)) return Outcome.STALE
            DurableResponse(
                body = raw.payload,
                contentType = raw.contentType,
                resolvedUrl = raw.resolvedUrl,
                fetchedAt = raw.fetchedAt,
            )
        }

        val extractionBaseUrl = response.resolvedUrl ?: canonicalUrl
        val extracted = try {
            extractor.extract(response.body, response.contentType, extractionBaseUrl)
        } catch (error: UnsupportedContentTypeException) {
            return skip(lease, canonicalUrl, error.message ?: "Unsupported content type")
        } catch (error: IllegalArgumentException) {
            return skip(lease, canonicalUrl, error.message ?: "Content cannot be extracted")
        }

        val contentHash = sha256(extracted.normalizedText)
        val fetchedMarkedAt = clock.instant()
        if (
            !ingestionRepository.markFetched(
                lease = lease,
                canonicalUrl = canonicalUrl,
                resolvedUrl = response.resolvedUrl ?: item.resolvedUrl,
                contentHash = contentHash,
                at = fetchedMarkedAt,
            )
        ) return Outcome.STALE

        val title = extracted.title?.takeIf { it.isNotBlank() }
            ?: item.title?.takeIf { it.isNotBlank() }
            ?: canonicalUrl
        val finalisedAt = clock.instant()
        val inboxId = InboxItemId("inbox-${sha256(canonicalUrl)}")
        return when (
            ingestionRepository.finalizeSuccess(
                lease = lease,
                item = InboxItem(
                    id = inboxId,
                    canonicalUrl = canonicalUrl,
                    title = title,
                    publishedAt = item.publishedAt,
                    normalizedText = extracted.normalizedText,
                    contentHash = contentHash,
                    createdAt = finalisedAt,
                    updatedAt = finalisedAt,
                ),
                origin = InboxOrigin(
                    inboxItemId = inboxId,
                    discoveredItemId = item.id,
                    sourceId = item.sourceId,
                    discoveredUrl = item.url,
                    resolvedUrl = response.resolvedUrl ?: item.resolvedUrl,
                    canonicalUrl = canonicalUrl,
                    discoveredAt = item.discoveredAt,
                    fetchedAt = response.fetchedAt,
                    sourceNameSnapshot = source.name,
                    sourceUrlSnapshot = source.url,
                    sourceTypeSnapshot = source.type.name,
                ),
                canonicalUrlHash = canonicalHash,
                completedAt = finalisedAt,
            )
        ) {
            IngestionFinalizeOutcome.ADDED_TO_INBOX -> Outcome.ADDED
            IngestionFinalizeOutcome.MERGED_INTO_INBOX -> Outcome.MERGED
            IngestionFinalizeOutcome.ALREADY_KNOWN -> Outcome.KNOWN
            IngestionFinalizeOutcome.STALE -> Outcome.STALE
        }
    }

    private suspend fun fail(
        lease: ArticleProcessingLease,
        failureAt: Instant,
        error: Exception,
        minimumDelay: Duration? = null,
    ): Outcome {
        val attempts = lease.item.processingAttempts + 1
        val multiplier = 1L shl min(attempts - 1, 10)
        val localDelay = retryBaseDelay.multipliedBy(multiplier).coerceAtMost(retryMaxDelay)
        val delay = minimumDelay?.takeIf { it > localDelay } ?: localDelay
        val applied = ingestionRepository.markFailed(
            lease = lease,
            processingAttempts = attempts,
            nextProcessingAt = failureAt.plus(delay),
            lastProcessingError = errorDescription(error),
            at = failureAt,
        )
        return if (applied) Outcome.FAILED else Outcome.STALE
    }

    private suspend fun skip(
        lease: ArticleProcessingLease,
        canonicalUrl: String?,
        reason: String,
    ): Outcome {
        val at = clock.instant()
        val applied = ingestionRepository.markSkipped(
            lease = lease,
            canonicalUrl = canonicalUrl,
            lastProcessingError = reason.take(500),
            at = at,
        )
        return if (applied) Outcome.SKIPPED else Outcome.STALE
    }

    private fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599

    private fun FetchResult.retryAfterHeader(): String? =
        fetchedHeaders.entries.firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }?.value

    private fun parseRetryAfter(value: String?, now: Instant): Duration? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return Duration.ofSeconds(seconds)
        }
        val retryAt = runCatching {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull() ?: return null
        return Duration.between(now, retryAt).takeUnless { it.isNegative }
    }

    private fun errorDescription(error: Exception): String =
        (error.message ?: error::class.java.simpleName).take(500)

    private fun Duration.coerceAtMost(other: Duration): Duration = if (this > other) other else this

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class DurableResponse(
        val body: ByteArray,
        val contentType: String?,
        val resolvedUrl: String?,
        val fetchedAt: Instant,
    )

    private class ArticleHttpException(statusCode: Int) :
        IllegalStateException("Article request returned HTTP $statusCode")

    private enum class Outcome { ADDED, MERGED, KNOWN, FAILED, SKIPPED, STALE }
}
