package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItem
import io.github.go0dboy.articlenavigator.core.model.DiscoveredItemId
import io.github.go0dboy.articlenavigator.core.model.DiscoveryStatus
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxOrigin
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InboxPagingIntegrationTest {
    private lateinit var database: ArticleNavigatorDatabase
    private lateinit var sources: RoomSourceRepository
    private lateinit var ingestion: RoomIngestionRepository
    private lateinit var inbox: RoomInboxRepository
    private lateinit var paging: RoomInboxPagingRepository

    private val now = Instant.parse("2026-09-16T09:30:00Z")
    private val source = Source(
        id = SourceId("paging-source"),
        name = "Paging source",
        type = SourceType.RSS,
        url = "https://example.test/feed.xml",
        enabled = true,
        pollPolicy = PollPolicy(Duration.ofHours(1)),
        adapterType = "rss-atom",
        createdAt = now.minusSeconds(3600),
        nextCheckAt = now,
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<ArticleNavigatorDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        sources = RoomSourceRepository(database.sourceDao(), database.sourceScheduleDao())
        ingestion = RoomIngestionRepository(database.ingestionDao(), database.articleProcessingDao())
        inbox = RoomInboxRepository(database.inboxDao(), database.inboxLifecycleDao())
        paging = RoomInboxPagingRepository(database.inboxPagingDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactCountAndKeysetPagingCoverMoreThanOnePageWithEqualDates() = runTest {
        sources.upsert(source)
        repeat(25) { index ->
            val suffix = index.toString().padStart(2, '0')
            val discovery = DiscoveredItem(
                id = DiscoveredItemId("discovery-$suffix"),
                sourceId = source.id,
                url = "https://example.test/$suffix",
                canonicalUrl = "https://example.test/$suffix",
                resolvedUrl = "https://example.test/$suffix",
                title = "Article $suffix",
                discoveredAt = now.minusSeconds(30),
                status = DiscoveryStatus.FETCHED,
            )
            ingestion.upsertDiscovered(discovery)
            val item = InboxItem(
                id = InboxItemId("inbox-$suffix"),
                canonicalUrl = discovery.url,
                title = "Inbox $suffix",
                normalizedText = "Body $suffix",
                contentHash = "hash-$suffix",
                createdAt = now,
                updatedAt = now,
            )
            inbox.put(
                item,
                InboxOrigin(
                    inboxItemId = item.id,
                    discoveredItemId = discovery.id,
                    sourceId = source.id,
                    discoveredUrl = discovery.url,
                    resolvedUrl = discovery.resolvedUrl,
                    canonicalUrl = discovery.canonicalUrl ?: discovery.url,
                    discoveredAt = discovery.discoveredAt,
                    fetchedAt = now,
                    sourceNameSnapshot = source.name,
                    sourceUrlSnapshot = source.url,
                    sourceTypeSnapshot = source.type.name,
                ),
            )
        }

        assertEquals(25, paging.observePendingCount().first())
        assertEquals(7, paging.loadPage(null, 7).size)

        val all = mutableListOf<InboxItemId>()
        var key: InboxPageKey? = null
        do {
            val page = paging.loadPage(key, 7)
            all += page.map { it.id }
            key = page.lastOrNull()?.let { InboxPageKey(it.createdAt, it.id) }
        } while (page.isNotEmpty())

        assertEquals(25, all.size)
        assertEquals(25, all.distinct().size)
        assertEquals((0 until 25).map { InboxItemId("inbox-${it.toString().padStart(2, '0')}") }.sortedByDescending { it.value }, all)

        assertTrue(inbox.discardCurrent(InboxItemId("inbox-24"), ContentDisposition.REJECTED, now.plusSeconds(1)))
        assertEquals(24, paging.observePendingCount().first())
    }
}
