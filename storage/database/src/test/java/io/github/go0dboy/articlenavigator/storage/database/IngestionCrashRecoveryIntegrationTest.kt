package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.data.IngestionFinalizeOutcome
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.pipeline.IngestionPipeline
import io.github.go0dboy.articlenavigator.pipeline.SourceAdapterResolver
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngestionCrashRecoveryIntegrationTest {
    private val baseTime = Instant.parse("2026-09-16T08:00:00Z")
    private val source = Source(
        id = SourceId("source"),
        name = "Recovery source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        createdAt = baseTime.minusSeconds(3600),
    )
    private val body = "<html><head><title>Recovered</title></head><body><article>Привет recovery</article></body></html>"
        .toByteArray(Charsets.UTF_8)

    @Test
    fun claimedWorkBecomesAvailableOnlyAfterPersistedLeaseExpiresAcrossReopen() = runTest {
        withDatabaseFile("claim") { file ->
            openDatabase(file).use { db ->
                seed(db, discovery("claimed"))
                val ingestion = runtime(db)
                assertNotNull(ingestion.tryClaimNext("owner-1", baseTime, baseTime.plusSeconds(30), true))
            }

            openDatabase(file).use { db ->
                val ingestion = runtime(db)
                assertNull(ingestion.tryClaimNext("too-early", baseTime.plusSeconds(20), baseTime.plusSeconds(60), true))
                val recovered = ingestion.tryClaimNext(
                    "owner-2",
                    baseTime.plusSeconds(31),
                    baseTime.plusSeconds(90),
                    true,
                )
                assertNotNull(recovered)
                assertEquals("owner-2", db.articleProcessingDao().item("claimed")?.processingLeaseToken)
            }
        }
    }

    @Test
    fun durableRawBytesResumeAfterReopenWithoutSecondHttpRequest() = runTest {
        withDatabaseFile("raw") { file ->
            val item = discovery("raw-recovery")
            val resolved = "https://cdn.example.test/articles/final"
            val contentType = "text/html; charset=utf-8; x-origin=test"
            openDatabase(file).use { db ->
                seed(db, item)
                val ingestion = runtime(db)
                val lease = checkNotNull(
                    ingestion.tryClaimNext("old", baseTime, baseTime.plusSeconds(1), true),
                )
                assertTrue(
                    ingestion.storeRawContent(
                        lease,
                        RawContent(
                            discoveredItemId = item.id,
                            contentType = contentType,
                            payload = body,
                            resolvedUrl = resolved,
                            fetchedAt = baseTime,
                            httpStatus = 200,
                            expiresAt = baseTime.plus(Duration.ofHours(1)),
                        ),
                        baseTime,
                    ),
                )
            }

            openDatabase(file).use { db ->
                val persisted = runtime(db).loadRawContent(item.id)
                assertNotNull(persisted)
                assertArrayEquals(body, persisted?.payload)
                assertEquals(contentType, persisted?.contentType)
                assertEquals(resolved, persisted?.resolvedUrl)

                val fetches = AtomicInteger(0)
                val report = pipeline(db, baseTime.plusSeconds(2)) {
                    fetches.incrementAndGet()
                    error("valid persisted raw must avoid HTTP")
                }.processReady(limit = 1)

                assertEquals(0, fetches.get())
                assertEquals(1, report.addedToInbox)
                assertEquals(DiscoveryStatus.PROCESSED, runtime(db).findDiscoveredById(item.id)?.status)
                assertNull(runtime(db).loadRawContent(item.id))
                val pending = inbox(db).listPending().single()
                val origin = inbox(db).origins(pending.id).single()
                assertEquals(resolved, origin.resolvedUrl)
                assertEquals(source.id, origin.sourceId)
                assertNull(db.articleProcessingDao().item(item.id.value)?.processingLeaseToken)
            }
        }
    }

    @Test
    fun fetchedIntermediateStateResumesAfterReopenWithoutHttp() = runTest {
        withDatabaseFile("fetched") { file ->
            val item = discovery("fetched-recovery")
            openDatabase(file).use { db ->
                seed(db, item)
                val ingestion = runtime(db)
                val lease = checkNotNull(ingestion.tryClaimNext("old", baseTime, baseTime.plusSeconds(1), true))
                val raw = RawContent(
                    item.id,
                    "text/html; charset=utf-8",
                    body,
                    "https://example.test/final-fetched",
                    baseTime,
                    200,
                    baseTime.plusSeconds(3600),
                )
                assertTrue(ingestion.storeRawContent(lease, raw, baseTime))
                assertTrue(
                    ingestion.markFetched(
                        lease,
                        item.url,
                        raw.resolvedUrl,
                        sha256("pre-finalized-content"),
                        baseTime,
                    ),
                )
            }

            openDatabase(file).use { db ->
                assertEquals(DiscoveryStatus.FETCHED, runtime(db).findDiscoveredById(item.id)?.status)
                val fetches = AtomicInteger(0)
                val report = pipeline(db, baseTime.plusSeconds(2)) {
                    fetches.incrementAndGet()
                    error("FETCHED work with valid raw must resume locally")
                }.processReady(limit = 1)
                assertEquals(0, fetches.get())
                assertEquals(1, report.addedToInbox)
                assertEquals(DiscoveryStatus.PROCESSED, runtime(db).findDiscoveredById(item.id)?.status)
                assertNull(runtime(db).loadRawContent(item.id))
            }
        }
    }

    @Test
    fun failedFinalizationRollsBackCompletelyAndWorkRecoversAfterReopen() = runTest {
        withDatabaseFile("rollback") { file ->
            val item = discovery("rollback-recovery")
            openDatabase(file).use { db ->
                seed(db, item)
                val ingestion = runtime(db)
                val lease = checkNotNull(ingestion.tryClaimNext("owner", baseTime, baseTime.plusSeconds(1), true))
                val raw = RawContent(
                    item.id,
                    "text/html; charset=utf-8",
                    body,
                    item.url,
                    baseTime,
                    200,
                    baseTime.plusSeconds(3600),
                )
                assertTrue(ingestion.storeRawContent(lease, raw, baseTime))
                assertTrue(ingestion.markFetched(lease, item.url, item.url, sha256("intermediate"), baseTime))

                val inboxItem = InboxItem(
                    InboxItemId("must-rollback"),
                    item.url,
                    "Rollback",
                    normalizedText = "rollback body",
                    contentHash = sha256("rollback body"),
                    createdAt = baseTime,
                    updatedAt = baseTime,
                )
                val badOrigin = InboxOrigin(
                    inboxItemId = inboxItem.id,
                    discoveredItemId = item.id,
                    sourceId = SourceId("missing-source-fk"),
                    discoveredUrl = item.url,
                    resolvedUrl = item.url,
                    canonicalUrl = item.url,
                    discoveredAt = item.discoveredAt,
                    fetchedAt = baseTime,
                    sourceNameSnapshot = "missing",
                    sourceUrlSnapshot = "https://missing.test/feed",
                    sourceTypeSnapshot = SourceType.RSS.name,
                )
                val failure = runCatching {
                    ingestion.finalizeSuccess(lease, inboxItem, badOrigin, sha256(item.url), baseTime)
                }.exceptionOrNull()
                assertNotNull("FK violation must abort the final transaction", failure)

                assertNull(inbox(db).findById(inboxItem.id))
                assertEquals(DiscoveryStatus.FETCHED, ingestion.findDiscoveredById(item.id)?.status)
                assertNotNull(ingestion.loadRawContent(item.id))
                assertEquals("owner", db.articleProcessingDao().item(item.id.value)?.processingLeaseToken)
            }

            openDatabase(file).use { db ->
                val fetches = AtomicInteger(0)
                val report = pipeline(db, baseTime.plusSeconds(2)) {
                    fetches.incrementAndGet()
                    error("rolled-back finalization must retain reusable raw")
                }.processReady(limit = 1)
                assertEquals(0, fetches.get())
                assertEquals(1, report.addedToInbox)
                assertEquals(1, inbox(db).listPending().size)
                assertEquals(DiscoveryStatus.PROCESSED, runtime(db).findDiscoveredById(item.id)?.status)
                assertNull(runtime(db).loadRawContent(item.id))
            }
        }
    }

    @Test
    fun expiredRawIsNotReusedAndSuccessfulRefetchReplacesIt() = runTest {
        withDatabaseFile("expired") { file ->
            val item = discovery("expired-raw")
            openDatabase(file).use { db ->
                seed(db, item)
                val ingestion = runtime(db)
                val lease = checkNotNull(ingestion.tryClaimNext("old", baseTime, baseTime.plusSeconds(1), true))
                assertTrue(
                    ingestion.storeRawContent(
                        lease,
                        RawContent(
                            item.id,
                            "text/html",
                            "<article>expired</article>".toByteArray(),
                            item.url,
                            baseTime,
                            200,
                            baseTime.plusSeconds(1),
                        ),
                        baseTime,
                    ),
                )
            }

            openDatabase(file).use { db ->
                val fetches = AtomicInteger(0)
                val report = pipeline(db, baseTime.plusSeconds(2)) { fetchedItem ->
                    fetches.incrementAndGet()
                    FetchResult(
                        item = fetchedItem,
                        statusCode = 200,
                        contentType = "text/html; charset=utf-8",
                        body = body,
                        resolvedUrl = "https://example.test/refetched",
                    )
                }.processReady(limit = 1)

                assertEquals(1, fetches.get())
                assertEquals(1, report.addedToInbox)
                assertNull(runtime(db).loadRawContent(item.id))
                assertEquals("https://example.test/refetched", inbox(db).origins(inbox(db).listPending().single().id).single().resolvedUrl)
            }
        }
    }

    private suspend fun seed(db: ArticleNavigatorDatabase, item: DiscoveredItem) {
        RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao()).upsert(source)
        runtime(db).upsertDiscovered(item)
    }

    private fun discovery(id: String) = DiscoveredItem(
        id = DiscoveredItemId(id),
        sourceId = source.id,
        url = "https://example.test/$id",
        canonicalUrl = "https://example.test/$id",
        title = "Article $id",
        discoveredAt = baseTime.minusSeconds(60),
    )

    private fun runtime(db: ArticleNavigatorDatabase) =
        RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())

    private fun inbox(db: ArticleNavigatorDatabase) =
        RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())

    private fun pipeline(
        db: ArticleNavigatorDatabase,
        at: Instant,
        fetch: suspend (DiscoveredItem) -> FetchResult,
    ): IngestionPipeline {
        val adapter = object : SourceAdapter {
            override val adapterType: String = "test"
            override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS)
            override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = error("not used")
            override suspend fun fetch(item: DiscoveredItem): FetchResult = fetch(item)
        }
        return IngestionPipeline(
            sourceRepository = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao()),
            ingestionRepository = runtime(db),
            adapterResolver = SourceAdapterResolver { adapter },
            clock = Clock.fixed(at, ZoneOffset.UTC),
        )
    }

    private fun openDatabase(file: Path): ArticleNavigatorDatabase =
        Room.databaseBuilder<ArticleNavigatorDatabase>(file.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .build()

    private suspend fun withDatabaseFile(name: String, block: suspend (Path) -> Unit) {
        val file = Files.createTempFile("article-navigator-$name", ".db")
        Files.deleteIfExists(file)
        try {
            block(file)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(Path.of(file.toString() + "-wal"))
            Files.deleteIfExists(Path.of(file.toString() + "-shm"))
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
