package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.ArticleProcessingLease
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.data.IngestionRepository
import io.github.go0dboy.articlenavigator.core.data.SourceRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionPipelineTest {
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val source = Source(
        id = SourceId("source-1"),
        name = "Feed",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofMinutes(30)),
        adapterType = "test",
        createdAt = now,
    )

    @Test
    fun `new article is extracted and placed into Inbox`() = runTest {
        val discovered = discovered("https://example.test/article")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion) {
            FetchResult(
                it,
                200,
                "text/html; charset=utf-8",
                "<html><head><title>Article title</title></head><body><article><p>Useful body text.</p></article></body></html>".toByteArray(),
            )
        }

        val report = pipeline.processReady()

        assertEquals(1, report.addedToInbox)
        assertEquals(0, report.failed)
        assertEquals(0, report.skipped)
        assertEquals(1, ingestion.inboxItems.size)
        assertEquals("Article title", ingestion.inboxItems.values.single().title)
        assertTrue(ingestion.inboxItems.values.single().normalizedText.contains("Useful body text."))
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
        assertEquals(null, ingestion.raw[discovered.id])
        assertEquals(null, ingestion.activeLeaseToken)
    }

    @Test
    fun `previously dismissed URL is not recreated`() = runTest {
        val discovered = discovered("https://example.test/seen")
        val ingestion = FakeIngestionRepository(discovered).apply {
            fingerprints += SeenFingerprint(
                canonicalUrlHash = sha256ForTest("https://example.test/seen"),
                contentHash = null,
                sourceId = source.id,
                seenAt = now.minusSeconds(60),
                disposition = ContentDisposition.REJECTED,
            )
        }
        var fetches = 0
        val pipeline = pipeline(ingestion) {
            fetches++
            FetchResult(it, 200, "text/html", "<article>Known body</article>".toByteArray())
        }

        val report = pipeline.processReady()

        assertEquals(1, report.alreadyKnown)
        assertEquals(1, fetches)
        assertEquals(0, ingestion.inboxItems.size)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
        assertEquals(ContentDisposition.REJECTED, ingestion.fingerprints.last().disposition)
    }

    @Test
    fun `network failure persists retry backoff`() = runTest {
        val discovered = discovered("https://example.test/failure")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion) { throw IOException("network down") }

        val report = pipeline.processReady()
        val failed = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.failed)
        assertEquals(0, report.skipped)
        assertEquals(DiscoveryStatus.FAILED, failed.status)
        assertEquals(1, failed.processingAttempts)
        assertEquals(now.plus(Duration.ofMinutes(15)), failed.nextProcessingAt)
        assertTrue(failed.lastProcessingError!!.contains("network down"))
        assertEquals(null, ingestion.activeLeaseToken)
    }

    @Test
    fun `server failure remains retryable`() = runTest {
        val discovered = discovered("https://example.test/server-failure")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion) { FetchResult(it, 503, "text/plain", "unavailable".toByteArray()) }

        val report = pipeline.processReady()
        val failed = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.failed)
        assertEquals(DiscoveryStatus.FAILED, failed.status)
        assertEquals(now.plus(Duration.ofMinutes(15)), failed.nextProcessingAt)
    }

    @Test
    fun `permanent client response is skipped without retry`() = runTest {
        val discovered = discovered("https://example.test/missing")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion) { FetchResult(it, 404, "text/plain", "missing".toByteArray()) }

        val report = pipeline.processReady()
        val skipped = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.skipped)
        assertEquals(0, report.failed)
        assertEquals(DiscoveryStatus.SKIPPED, skipped.status)
        assertEquals(null, skipped.nextProcessingAt)
        assertTrue(skipped.lastProcessingError!!.contains("404"))
        assertEquals(null, ingestion.raw[discovered.id])
    }

    @Test
    fun `unsupported content is skipped and temporary payload is removed`() = runTest {
        val discovered = discovered("https://example.test/image")
        val ingestion = FakeIngestionRepository(discovered)
        val pipeline = pipeline(ingestion) { FetchResult(it, 200, "image/png", byteArrayOf(1, 2, 3)) }

        val report = pipeline.processReady()
        val skipped = ingestion.items.getValue(discovered.id)

        assertEquals(1, report.skipped)
        assertEquals(DiscoveryStatus.SKIPPED, skipped.status)
        assertEquals(null, ingestion.raw[discovered.id])
        assertTrue(skipped.lastProcessingError!!.contains("Unsupported content type"))
    }

    @Test
    fun `same normalized content merges provenance into existing Inbox item`() = runTest {
        val discovered = discovered("https://example.test/alternate")
        val body = "<article><p>Same durable content</p></article>".toByteArray()
        val extracted = DefaultContentExtractor().extract(body, "text/html", discovered.url)
        val existing = InboxItem(
            id = InboxItemId("existing"),
            canonicalUrl = "https://example.test/original",
            title = "Original",
            normalizedText = extracted.normalizedText,
            contentHash = sha256ForTest(extracted.normalizedText),
            createdAt = now.minusSeconds(10),
            updatedAt = now.minusSeconds(10),
        )
        val ingestion = FakeIngestionRepository(discovered).apply { inboxItems[existing.id] = existing }
        val pipeline = pipeline(ingestion) { FetchResult(it, 200, "text/html", body) }

        val report = pipeline.processReady()

        assertEquals(1, report.mergedIntoInbox)
        assertEquals(1, ingestion.inboxItems.size)
        assertEquals(1, ingestion.inboxOrigins.getValue(existing.id).size)
        assertEquals(discovered.id, ingestion.inboxOrigins.getValue(existing.id).single().discoveredItemId)
        assertEquals(DiscoveryStatus.PROCESSED, ingestion.items.getValue(discovered.id).status)
    }

    private fun pipeline(
        ingestion: FakeIngestionRepository,
        fetch: suspend (DiscoveredItem) -> FetchResult,
    ) = IngestionPipeline(
        sourceRepository = PipelineTestSourceRepository(source),
        ingestionRepository = ingestion,
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

    private fun discovered(url: String) = DiscoveredItem(
        id = DiscoveredItemId(url.substringAfterLast('/')),
        sourceId = source.id,
        url = url,
        canonicalUrl = url,
        title = "Feed title",
        discoveredAt = now.minusSeconds(30),
    )
}

private class PipelineTestSourceRepository(private val source: Source) : SourceRepository {
    override suspend fun upsert(source: Source) = Unit
    override suspend fun findById(id: SourceId): Source? = source.takeIf { it.id == id }
    override suspend fun findDue(now: Instant): List<Source> = emptyList()
    override suspend fun listAll(): List<Source> = listOf(source)
    override suspend fun loadCursor(sourceId: SourceId): SourceCursor? = null
    override suspend fun saveCursor(cursor: SourceCursor) = Unit
    override suspend fun markDue(sourceId: SourceId, at: Instant): Boolean = false
}

private class FakeIngestionRepository(initial: DiscoveredItem) : IngestionRepository {
    val items = linkedMapOf(initial.id to initial)
    val raw = mutableMapOf<DiscoveredItemId, RawContent>()
    val inboxItems = linkedMapOf<InboxItemId, InboxItem>()
    val inboxOrigins = mutableMapOf<InboxItemId, MutableList<InboxOrigin>>()
    val fingerprints = mutableListOf<SeenFingerprint>()
    var activeLeaseToken: String? = null
        private set
    private var activeLeaseExpiresAt: Instant? = null

    override suspend fun tryClaimNext(
        runToken: String,
        now: Instant,
        leaseExpiresAt: Instant,
        isUnmeteredNetwork: Boolean,
    ): ArticleProcessingLease? {
        if (activeLeaseToken != null && activeLeaseExpiresAt?.isAfter(now) == true) return null
        val candidate = items.values.firstOrNull { item ->
            val retryAt = item.nextProcessingAt
            item.status == DiscoveryStatus.DISCOVERED || item.status == DiscoveryStatus.FETCHED ||
                (item.status == DiscoveryStatus.FAILED && (retryAt == null || !retryAt.isAfter(now)))
        } ?: return null
        activeLeaseToken = runToken
        activeLeaseExpiresAt = leaseExpiresAt
        return ArticleProcessingLease(candidate, runToken, leaseExpiresAt)
    }

    override suspend fun releaseProcessing(lease: ArticleProcessingLease) {
        if (activeLeaseToken == lease.runToken) clearLease()
    }

    override suspend fun storeRawContent(lease: ArticleProcessingLease, content: RawContent, at: Instant): Boolean {
        if (!owns(lease, at)) return false
        raw[content.discoveredItemId] = content
        return true
    }

    override suspend fun loadRawContent(lease: ArticleProcessingLease): RawContent? = raw[lease.item.id]

    override suspend fun markFetched(
        lease: ArticleProcessingLease,
        canonicalUrl: String,
        resolvedUrl: String?,
        contentHash: String,
        at: Instant,
    ): Boolean {
        if (!owns(lease, at)) return false
        val current = items[lease.item.id] ?: return false
        items[lease.item.id] = current.copy(
            canonicalUrl = canonicalUrl,
            resolvedUrl = resolvedUrl ?: current.resolvedUrl,
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
        val current = items[lease.item.id] ?: return false
        items[lease.item.id] = current.copy(
            status = DiscoveryStatus.FAILED,
            processingAttempts = processingAttempts,
            nextProcessingAt = nextProcessingAt,
            lastProcessingError = lastProcessingError,
        )
        clearLease()
        return true
    }

    override suspend fun markSkipped(
        lease: ArticleProcessingLease,
        canonicalUrl: String?,
        lastProcessingError: String,
        at: Instant,
    ): Boolean {
        if (!owns(lease, at)) return false
        val current = items[lease.item.id] ?: return false
        items[lease.item.id] = current.copy(
            canonicalUrl = canonicalUrl ?: current.canonicalUrl,
            status = DiscoveryStatus.SKIPPED,
            nextProcessingAt = null,
            lastProcessingError = lastProcessingError,
        )
        raw.remove(lease.item.id)
        clearLease()
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
        val dismissed = fingerprints.lastOrNull {
            (it.canonicalUrlHash == canonicalUrlHash || (it.contentHash != null && it.contentHash == item.contentHash)) &&
                (it.disposition == ContentDisposition.REJECTED || it.disposition == ContentDisposition.READ_AND_DISCARDED)
        }
        val outcome = if (dismissed != null) {
            fingerprints += SeenFingerprint(
                canonicalUrlHash = canonicalUrlHash,
                contentHash = item.contentHash,
                sourceId = origin.sourceId,
                seenAt = completedAt,
                disposition = dismissed.disposition,
            )
            IngestionFinalizeOutcome.ALREADY_KNOWN
        } else {
            val existing = inboxItems.values.firstOrNull {
                it.canonicalUrl == item.canonicalUrl || it.contentHash == item.contentHash
            }
            if (existing != null) {
                inboxOrigins.getOrPut(existing.id) { mutableListOf() }.add(origin.copy(inboxItemId = existing.id))
                IngestionFinalizeOutcome.MERGED_INTO_INBOX
            } else {
                inboxItems[item.id] = item
                inboxOrigins.getOrPut(item.id) { mutableListOf() }.add(origin)
                IngestionFinalizeOutcome.ADDED_TO_INBOX
            }
        }
        val current = items.getValue(lease.item.id)
        items[lease.item.id] = current.copy(
            canonicalUrl = item.canonicalUrl,
            resolvedUrl = origin.resolvedUrl ?: current.resolvedUrl,
            contentHash = item.contentHash,
            status = DiscoveryStatus.PROCESSED,
            nextProcessingAt = null,
            lastProcessingError = null,
        )
        raw.remove(lease.item.id)
        clearLease()
        return outcome
    }

    private fun owns(lease: ArticleProcessingLease, at: Instant): Boolean =
        activeLeaseToken == lease.runToken && activeLeaseExpiresAt?.isAfter(at) == true

    private fun clearLease() {
        activeLeaseToken = null
        activeLeaseExpiresAt = null
    }
}

private fun sha256ForTest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
