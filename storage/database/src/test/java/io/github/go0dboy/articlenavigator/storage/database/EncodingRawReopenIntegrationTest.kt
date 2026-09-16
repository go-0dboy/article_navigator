package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.DiscoveryResult
import io.github.go0dboy.articlenavigator.collector.api.FetchResult
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapter
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.RawContent
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceCursor
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.pipeline.IngestionPipeline
import io.github.go0dboy.articlenavigator.pipeline.SourceAdapterResolver
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncodingRawReopenIntegrationTest {
    private val baseTime = Instant.parse("2026-09-16T09:00:00Z")
    private val source = Source(
        id = SourceId("encoding-source"),
        name = "Encoding source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "test",
        createdAt = baseTime.minusSeconds(3600),
    )

    @Test
    fun windows1251RawSurvivesReopenAndExtractsWithoutSecondHttpRequest() = runTest {
        withDatabaseFile("cp1251") { file ->
            val item = DiscoveredItem(
                id = DiscoveredItemId("cp1251-item"),
                sourceId = source.id,
                url = "https://example.test/cp1251",
                canonicalUrl = "https://example.test/cp1251",
                title = "CP1251",
                discoveredAt = baseTime.minusSeconds(30),
            )
            val exactText = "Привет из сохранённого raw. Вторая строка."
            val payload = exactText.toByteArray(Charset.forName("windows-1251"))
            val resolvedUrl = "https://cdn.example.test/final-cp1251"

            withOpenDatabase(file) { db ->
                RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao()).upsert(source)
                val ingestion = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao())
                ingestion.upsertDiscovered(item)
                val lease = checkNotNull(
                    ingestion.tryClaimNext("first", baseTime, baseTime.plusSeconds(1), true),
                )
                check(
                    ingestion.storeRawContent(
                        lease,
                        RawContent(
                            discoveredItemId = item.id,
                            contentType = "text/plain; charset=windows-1251",
                            payload = payload,
                            resolvedUrl = resolvedUrl,
                            fetchedAt = baseTime,
                            httpStatus = 200,
                            expiresAt = baseTime.plusSeconds(3600),
                        ),
                        baseTime,
                    ),
                )
            }

            withOpenDatabase(file) { db ->
                val fetchCount = AtomicInteger(0)
                val adapter = object : SourceAdapter {
                    override val adapterType: String = "test"
                    override val supportedTypes: Set<SourceType> = setOf(SourceType.RSS)
                    override suspend fun discover(source: Source, cursor: SourceCursor?): DiscoveryResult = error("not used")
                    override suspend fun fetch(item: DiscoveredItem): FetchResult {
                        fetchCount.incrementAndGet()
                        error("valid persisted raw must be used after reopen")
                    }
                }
                val pipeline = IngestionPipeline(
                    sourceRepository = RoomSourceRepository(db.sourceDao(), db.sourceScheduleDao()),
                    ingestionRepository = RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao()),
                    adapterResolver = SourceAdapterResolver { adapter },
                    clock = Clock.fixed(baseTime.plusSeconds(2), ZoneOffset.UTC),
                )

                val report = pipeline.processReady(limit = 1)

                assertEquals(0, fetchCount.get())
                assertEquals(1, report.addedToInbox)
                val inbox = RoomInboxRepository(db.inboxDao(), db.inboxLifecycleDao())
                val pending = inbox.listPending().single()
                assertEquals(exactText, pending.normalizedText)
                assertEquals(resolvedUrl, inbox.origins(pending.id).single().resolvedUrl)
                assertNull(RoomIngestionRepository(db.ingestionDao(), db.articleProcessingDao()).loadRawContent(item.id))
            }
        }
    }

    private fun openDatabase(file: Path): ArticleNavigatorDatabase =
        Room.databaseBuilder<ArticleNavigatorDatabase>(file.toAbsolutePath().toString())
            .setDriver(BundledSQLiteDriver())
            .build()

    private suspend fun <T> withOpenDatabase(file: Path, block: suspend (ArticleNavigatorDatabase) -> T): T {
        val database = openDatabase(file)
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

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
}
