package io.github.go0dboy.articlenavigator

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.SourceAdapterRegistry
import io.github.go0dboy.articlenavigator.collector.rss.RssAtomSourceAdapter
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.core.network.OkHttpTransport
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkScheduler
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencies
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionOrchestrator
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunContext
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunReport
import io.github.go0dboy.articlenavigator.storage.database.ArticleNavigatorDatabase
import io.github.go0dboy.articlenavigator.storage.database.MIGRATION_1_2
import io.github.go0dboy.articlenavigator.storage.database.RoomCollectionStateRepository
import io.github.go0dboy.articlenavigator.storage.database.RoomIngestionRepository
import io.github.go0dboy.articlenavigator.storage.database.RoomSourceRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AppContainer(
    private val context: Context,
) : CollectionWorkerDependencies {
    private val database = Room.databaseBuilder<ArticleNavigatorDatabase>(
        context = context,
        name = "article-navigator.db",
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .build()

    private val sourceRepository = RoomSourceRepository(database.sourceDao())
    private val ingestionRepository = RoomIngestionRepository(database.ingestionDao())
    private val stateRepository = RoomCollectionStateRepository(database.collectionStateDao())

    private val orchestrator = CollectionOrchestrator(
        sourceRepository = sourceRepository,
        ingestionRepository = ingestionRepository,
        stateRepository = stateRepository,
        adapterRegistry = SourceAdapterRegistry(
            listOf(RssAtomSourceAdapter(OkHttpTransport())),
        ),
        maxParallelism = 4,
    )

    override suspend fun runCollection(isUnmeteredNetwork: Boolean): CollectionRunReport =
        orchestrator.run(CollectionRunContext(isUnmeteredNetwork))

    suspend fun ensureSampleSource(forceDue: Boolean = false) {
        val now = Instant.now()
        val existing = sourceRepository.findById(SAMPLE_SOURCE_ID)
        if (existing == null) {
            sourceRepository.upsert(
                Source(
                    id = SAMPLE_SOURCE_ID,
                    name = "Phase 3 device sample",
                    type = SourceType.RSS,
                    url = SAMPLE_FEED_URL,
                    enabled = true,
                    pollPolicy = PollPolicy(Duration.ofMinutes(15)),
                    adapterType = RssAtomSourceAdapter.ADAPTER_TYPE,
                    createdAt = now,
                    nextCheckAt = now,
                ),
            )
        } else if (forceDue) {
            sourceRepository.upsert(existing.copy(nextCheckAt = now))
        }
    }

    suspend fun enqueueImmediateCollection(): UUID {
        ensureSampleSource(forceDue = true)
        return CollectionWorkScheduler.runNow(context)
    }

    suspend fun loadDeviceStatus(): DeviceStatus {
        val source = sourceRepository.findById(SAMPLE_SOURCE_ID)
        val state = stateRepository.load(SAMPLE_SOURCE_ID)
        val latest = database.ingestionDao().latestDiscovered(5)
        return DeviceStatus(
            sourceName = source?.name ?: "Sample source is not initialized",
            sourceUrl = source?.url ?: SAMPLE_FEED_URL,
            lastSuccessfulCheckAt = source?.lastSuccessfulCheckAt,
            nextCheckAt = source?.nextCheckAt,
            lastAttemptAt = state?.lastAttemptAt,
            consecutiveFailures = state?.consecutiveFailures ?: 0,
            lastError = listOfNotNull(state?.lastErrorType, state?.lastErrorMessage)
                .joinToString(": ")
                .ifBlank { null },
            lastDiscoveredCount = state?.lastDiscoveredCount ?: 0,
            totalDiscoveredCount = database.ingestionDao().countDiscovered(),
            latestTitles = latest.map { it.title ?: it.url },
        )
    }

    companion object {
        val SAMPLE_SOURCE_ID = SourceId("phase3-sample-rss")
        const val SAMPLE_FEED_URL =
            "https://raw.githubusercontent.com/go-0dboy/article_navigator/main/docs/device-test-feed.xml"
    }
}

data class DeviceStatus(
    val sourceName: String,
    val sourceUrl: String,
    val lastSuccessfulCheckAt: Instant?,
    val nextCheckAt: Instant?,
    val lastAttemptAt: Instant?,
    val consecutiveFailures: Int,
    val lastError: String?,
    val lastDiscoveredCount: Int,
    val totalDiscoveredCount: Int,
    val latestTitles: List<String>,
)
