package io.github.go0dboy.articlenavigator

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.go0dboy.articlenavigator.collector.api.UrlCanonicalizer
import io.github.go0dboy.articlenavigator.collector.rss.RssAtomSourceAdapter
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.PollPolicy
import io.github.go0dboy.articlenavigator.core.model.Source
import io.github.go0dboy.articlenavigator.core.model.SourceId
import io.github.go0dboy.articlenavigator.core.model.SourceType
import io.github.go0dboy.articlenavigator.core.network.OkHttpTransport
import io.github.go0dboy.articlenavigator.pipeline.InboxService
import io.github.go0dboy.articlenavigator.pipeline.IngestionPipeline
import io.github.go0dboy.articlenavigator.pipeline.SourceAdapterResolver
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkScheduler
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencies
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionOrchestrator
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunContext
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunReport
import io.github.go0dboy.articlenavigator.scheduler.core.SourceAdapterRegistry
import io.github.go0dboy.articlenavigator.storage.database.ArticleNavigatorDatabase
import io.github.go0dboy.articlenavigator.storage.database.MIGRATION_1_2
import io.github.go0dboy.articlenavigator.storage.database.MIGRATION_2_3
import io.github.go0dboy.articlenavigator.storage.database.RoomCollectionStateRepository
import io.github.go0dboy.articlenavigator.storage.database.RoomInboxRepository
import io.github.go0dboy.articlenavigator.storage.database.RoomIngestionRepository
import io.github.go0dboy.articlenavigator.storage.database.RoomKnowledgeRepository
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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()

    private val sourceRepository = RoomSourceRepository(database.sourceDao())
    private val ingestionRepository = RoomIngestionRepository(database.ingestionDao())
    private val stateRepository = RoomCollectionStateRepository(database.collectionStateDao())
    private val inboxRepository = RoomInboxRepository(database.inboxDao())
    private val knowledgeRepository = RoomKnowledgeRepository(database.documentDao())

    private val adapterRegistry = SourceAdapterRegistry(
        listOf(RssAtomSourceAdapter(OkHttpTransport())),
    )

    private val orchestrator = CollectionOrchestrator(
        sourceRepository = sourceRepository,
        ingestionRepository = ingestionRepository,
        stateRepository = stateRepository,
        adapterRegistry = adapterRegistry,
        maxParallelism = 4,
    )

    private val ingestionPipeline = IngestionPipeline(
        sourceRepository = sourceRepository,
        ingestionRepository = ingestionRepository,
        inboxRepository = inboxRepository,
        knowledgeRepository = knowledgeRepository,
        adapterResolver = SourceAdapterResolver(adapterRegistry::resolve),
    )

    private val inboxService = InboxService(inboxRepository)

    override suspend fun runCollection(isUnmeteredNetwork: Boolean): CollectionRunReport {
        val diagnosticWasEnabled = sourceRepository.findById(SAMPLE_SOURCE_ID)?.enabled == true
        return try {
            val report = orchestrator.run(CollectionRunContext(isUnmeteredNetwork))
            ingestionPipeline.processReady(limit = 20)
            report
        } finally {
            if (diagnosticWasEnabled) {
                sourceRepository.findById(SAMPLE_SOURCE_ID)?.let { source ->
                    sourceRepository.upsert(source.copy(enabled = false))
                }
            }
        }
    }

    suspend fun ensureSampleSource(forceDue: Boolean = false) {
        val now = Instant.now()
        val existing = sourceRepository.findById(SAMPLE_SOURCE_ID)
        if (existing == null) {
            sourceRepository.upsert(
                Source(
                    id = SAMPLE_SOURCE_ID,
                    name = "Article Navigator device sample",
                    type = SourceType.RSS,
                    url = SAMPLE_FEED_URL,
                    enabled = true,
                    pollPolicy = PollPolicy(Duration.ofMinutes(15)),
                    adapterType = RssAtomSourceAdapter.ADAPTER_TYPE,
                    createdAt = now,
                    nextCheckAt = now,
                ),
            )
        } else {
            sourceRepository.upsert(
                existing.copy(
                    name = "Article Navigator device sample",
                    url = SAMPLE_FEED_URL,
                    enabled = true,
                    nextCheckAt = if (forceDue) now else existing.nextCheckAt,
                ),
            )
        }
    }

    suspend fun addRssSource(name: String, rawUrl: String): SourceId {
        val cleanName = name.trim().ifBlank { "RSS source" }
        val canonicalUrl = UrlCanonicalizer.canonicalize(rawUrl.trim())
            ?: throw IllegalArgumentException("Нужен корректный HTTP/HTTPS URL RSS или Atom")
        val existing = sourceRepository.listAll().firstOrNull { it.id != SAMPLE_SOURCE_ID && it.url == canonicalUrl }
        if (existing != null) {
            sourceRepository.upsert(existing.copy(name = cleanName, enabled = true, nextCheckAt = Instant.now()))
            return existing.id
        }

        val now = Instant.now()
        val id = SourceId.new()
        sourceRepository.upsert(
            Source(
                id = id,
                name = cleanName,
                type = SourceType.RSS,
                url = canonicalUrl,
                enabled = true,
                pollPolicy = PollPolicy(Duration.ofHours(1)),
                adapterType = RssAtomSourceAdapter.ADAPTER_TYPE,
                createdAt = now,
                nextCheckAt = now,
            ),
        )
        return id
    }

    suspend fun listSources(): List<Source> = sourceRepository.listAll().filterNot { it.id == SAMPLE_SOURCE_ID }

    suspend fun enqueueImmediateCollection(): UUID {
        val now = Instant.now()
        sourceRepository.listAll()
            .filter { it.enabled && it.id != SAMPLE_SOURCE_ID }
            .forEach { sourceRepository.upsert(it.copy(nextCheckAt = now)) }
        return CollectionWorkScheduler.runNow(context)
    }

    suspend fun enqueueDeviceSampleCollection(): UUID {
        ensureSampleSource(forceDue = true)
        return CollectionWorkScheduler.runNow(context)
    }

    suspend fun loadInbox(): List<InboxItem> = inboxService.list()

    suspend fun rejectInbox(id: InboxItemId) = inboxService.reject(id)

    suspend fun readAndDiscardInbox(id: InboxItemId) = inboxService.readAndDiscard(id)

    suspend fun saveInbox(id: InboxItemId): DocumentId = inboxService.save(id)

    suspend fun loadDeviceStatus(): DeviceStatus {
        val source = sourceRepository.findById(SAMPLE_SOURCE_ID)
        val state = stateRepository.load(SAMPLE_SOURCE_ID)
        val latest = database.ingestionDao().latestDiscovered(5)
        return DeviceStatus(
            sourceName = source?.name ?: "Контрольный источник не добавлен",
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
            pendingInboxCount = inboxService.list().size,
            latestTitles = latest.map { it.title ?: it.url },
        )
    }

    companion object {
        val SAMPLE_SOURCE_ID = SourceId("phase3-sample-rss")
        const val SAMPLE_FEED_URL =
            "https://raw.githubusercontent.com/go-0dboy/article_navigator/ce7432a3bb6bfec6aa3ce264c89f6add49484379/docs/device-test-feed.xml"
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
    val pendingInboxCount: Int,
    val latestTitles: List<String>,
)
