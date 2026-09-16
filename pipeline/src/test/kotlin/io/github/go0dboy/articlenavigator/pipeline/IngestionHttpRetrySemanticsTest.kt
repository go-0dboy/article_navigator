package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.ArticleProcessingLease
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngestionHttpRetrySemanticsTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private val source = Source(
        id = SourceId("source"),
        name = "Source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        createdAt = now.minusSeconds(3600),
    )

    @Test
    fun retryAfterSecondsOverridesLocalBackoffOnlyWhenLonger() = runTest {
        listOf(429 to 1200L, 503 to 1800L).forEach { (status, seconds) ->
            val stored = runResponse(status, mapOf("Retry-After" to seconds.toString()))
            assertEquals(DiscoveryStatus.FAILED, stored.status)
            assertEquals(now.plusSeconds(seconds), stored.nextProcessingAt)
        }
    }

    @Test
    fun shortRetryAfterDoesNotReduceLocalBackoff() = runTest {
        val stored = runResponse(503, mapOf("Retry-After" to "45"))
        assertEquals(DiscoveryStatus.FAILED, stored.status)
        assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
    }

    @Test
    fun retryAfterHttpDateOverridesLocalBackoff() = runTest {
        val header = ZonedDateTime.ofInstant(now.plusSeconds(1800), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        val stored = runResponse(503, mapOf("Retry-After" to header))
        assertEquals(now.plusSeconds(1800), stored.nextProcessingAt)
    }

    @Test
    fun pastOrInvalidRetryAfterFallsBackToLocalBackoff() = runTest {
        val past = ZonedDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)
        listOf(past, "not-a-date").forEach { value ->
            val stored = runResponse(503, mapOf("Retry-After" to value))
            assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
        }
    }

    @Test
    fun permanentClientErrorsAreSkippedWithoutRetry() = runTest {
        listOf(401, 403, 404).forEach { status ->
            val stored = runResponse(status)
            assertEquals(DiscoveryStatus.SKIPPED, stored.status)
            assertNull(stored.nextProcessingAt)
            assertEquals(0, stored.processingAttempts)
        }
    }

    @Test
    fun http500UsesTransientLocalBackoff() = runTest {
        val stored = runResponse(500)
        assertEquals(DiscoveryStatus.FAILED, stored.status)
        assertEquals(1, stored.processingAttempts)
        assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
    }

    @Test
    fun transportTimeoutUsesTransientLocalBackoff() = runTest {
        val repository = RetryIngestionRepository(discovered())
        val pipeline = pipeline(repository, Clock.fixed(now, ZoneOffset.UTC)) { throw SocketTimeoutException("timed out") }
        pipeline.processReady()
        val stored = repository.item
        assertEquals(DiscoveryStatus.FAILED, stored.status)
        assertEquals(now.plus(Duration.ofMinutes(15)), stored.nextProcessingAt)
    }

    @Test
    fun retryAfterIsMeasuredFromResponseTimeNotBatchStart() = runTest {
        val clock = MutableTestClock(now)
        val repository = RetryIngestionRepository(discovered())
        val pipeline = pipeline(repository, clock) { item ->
            clock.advance(Duration.ofMinutes(7))
            FetchResult(
                item = item,
                statusCode = 503,
                contentType = "text/plain",
                body = "later".toByteArray(),
                fetchedHeaders = mapOf("Retry-After" to "1200"),
            )
        }

        pipeline.processReady()

        assertEquals(now.plus(Duration.ofMinutes(27)), repository.item.nextProcessingAt)
    }

    private suspend fun runResponse(status: Int, headers: Map<String, String> = emptyMap()): DiscoveredItem {
        val repository = RetryIngestionRepository(discovered())
        val pipeline = pipeline(repository, Clock.fixed(now, ZoneOffset.UTC)) { item ->
            FetchResult(
                item = item,
                statusCode = status,
                contentType = "text/plain",
                body = "response".toByteArray(),
                fetchedHeaders = headers,
            )
        }
        pipeline.processReady()
        return repository.item
    }

    private fun pipeline(
        repository: RetryIngestionRepository,
        clock: Clock,
        fetch: suspend (DiscoveredItem) -> FetchResult,
    ) = IngestionPipeline(
        sourceRepository = SingleSourceRepository(source),
        ingestionRepository = repository,
        adapterResolver = SourceAdapterResolver {
            object : SourceAdapter {
                override val adapterType = "test"
                override val supportedTypes = setOf(SourceType.RSS)
                override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = error("not used")
                override suspend fun fetch(item: DiscoveredItem): FetchResult = fetch(item)
            }
        },
        clock = clock,
    )

    private fun discovered() = DiscoveredItem(
        id = DiscoveredItemId("item"),
        sourceId = source.id,
        url = "https://example.test/article",
        canonicalUrl = "https://example.test/article",
        discoveredAt = now.minusSeconds(30),
    )
}

private class RetryIngestionRepository(initial: DiscoveredItem) : IngestionRepository {
    var item: DiscoveredItem = initial
    private var raw: RawContent? = null
    private var token: String? = null
    private var expiresAt: Instant? = null

    override suspend fun tryClaimNext(
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
        isUnmeteredNetwork: Boolean,
    ): ArticleProcessingLease? {
        val retryDue = item.nextProcessingAt?.let { !it.isAfter(now) } ?: true
        val statusReady = item.status == DiscoveryStatus.DISCOVERED || item.status == DiscoveryStatus.FETCHED ||
            (item.status == DiscoveryStatus.FAILED && retryDue)
        val leaseFree = token == null || expiresAt?.let { !it.isAfter(now) } != false
        if (!statusReady || !leaseFree) return null
        token = runToken
        expiresAt = leaseExpiresAt
        return ArticleProcessingLease(item, runToken, leaseExpiresAt)
    }

    override suspend fun releaseProcessing(lease: ArticleProcessingLease) {
        if (token == lease.runToken) {
            token = null
            expiresAt = null
        }
    }

    override suspend fun storeRawContent(lease: ArticleProcessingLease, content: RawContent, at: Instant): Boolean {
        if (!owns(lease, at)) return false
        raw = content
        return true
    }

    override suspend fun loadRawContent(lease: ArticleProcessingLease): RawContent? = raw

    override suspend fun markFetched(
        lease: ArticleProcessingLease,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
        at: Instant,
    ): Boolean {
        if (!owns(lease, at)) return false
        item = item.copy(
            canonicalUrl = canonicalUrl,
            resolvedUrl = resolvedUrl ?: item.resolvedUrl,
            contentHash = contentHash,
            status = DiscoveryStatus.FETCHED,
            nextProcessingAt = null,
            lastProcessingError = null,
        )
        return true
    }

    override suspend fun markFailed(
        lease: ArticleProcessingLease,
        processingAttempts: Int,
        nextProcessingAt: Instant,
        lastProcessingError: String,
        at: Instant,
    ): Boolean {
        if (!owns(lease, at)) return false
        item = item.copy(
            status = DiscoveryStatus.FAILED,
            processingAttempts = processingAttempts,
            nextProcessingAt = nextProcessingAt,
            lastProcessingError = lastProcessingError,
        )
        token = null
        expiresAt = null
        return true
    }

    override suspend fun markSkipped(
        lease: ArticleProcessingLease,
        canonicalUrl: String?,
        lastProcessingError: String,
        at: Instant,
    ): Boolean {
        if (!owns(lease, at)) return false
        item = item.copy(
            canonicalUrl = canonicalUrl ?: item.canonicalUrl,
            status = DiscoveryStatus.SKIPPED,
            nextProcessingAt = null,
            lastProcessingError = lastProcessingError,
        )
        raw = null
        token = null
        expiresAt = null
        return true
    }

    override suspend fun finalizeSuccess(
        lease: ArticleProcessingLease,
        item: InboxItem,
        origin: InboxOrigin,
        canonicalUrlHash: String,
        completedAt: Instant,
    ): IngestionFinalizeOutcome {
        if (!owns(lease, completedAt)) return IngestionFinalizeOutcome.STALE
        this.item = this.item.copy(
            canonicalUrl = item.canonicalUrl,
            resolvedUrl = origin.resolvedUrl ?: this.item.resolvedUrl,
            contentHash = item.contentHash,
            status = DiscoveryStatus.PROCESSED,
            nextProcessingAt = null,
            lastProcessingError = null,
        )
        raw = null
        token = null
        expiresAt = null
        return IngestionFinalizeOutcome.ADDED_TO_INBOX
    }

    private fun owns(lease: ArticleProcessingLease, at: Instant): Boolean =
        token == lease.runToken && expiresAt?.isAfter(at) == true
}

private class SingleSourceRepository(private val source: Source) : SourceRepository {
    override suspend fun upsert(source: Source) = Unit
    override suspend fun findById(id: SourceId): Source? = source.takeIf { it.id == id }
    override suspend fun findDue(now: Instant): List<Source> = emptyList()
    override suspend fun listAll(): List<Source> = listOf(source)
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = null
    override suspend fun saveCursor(cursor: SourceCursor) = Unit
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean = false
}

private class MutableTestClock(
    private var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
