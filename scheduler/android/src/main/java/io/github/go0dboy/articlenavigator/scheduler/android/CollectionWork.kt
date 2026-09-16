package io.github.go0dboy.articlenavigator.scheduler.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunReport
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

data class IngestionRunDiagnostics(
    val processed: Int,
    val addedToInbox: Int,
    val mergedIntoInbox: Int,
    val alreadyKnown: Int,
    val failed: Int,
    val skipped: Int,
    val stale: Int,
    val budgetExhausted: Boolean,
)

data class CollectionPassReport(
    val collection: CollectionRunReport,
    val ingestion: IngestionRunDiagnostics,
) {
    val continuationRequired: Boolean
        get() = collection.budgetExhausted || ingestion.budgetExhausted
}

fun interface CollectionWorkerDependencies {
    suspend fun runCollection(isUnmeteredNetwork: Boolean): CollectionPassReport
}

interface CollectionWorkerDependencyProvider {
    val collectionWorkerDependencies: CollectionWorkerDependencies
}

class CollectionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val provider = applicationContext as? CollectionWorkerDependencyProvider
            ?: return Result.failure(workDataOf(KEY_ERROR to "Application does not provide collection dependencies"))

        return try {
            val report = provider.collectionWorkerDependencies.runCollection(
                isUnmeteredNetwork = isUnmeteredNetwork(applicationContext),
            )
            val diagnostics = diagnosticsData(report)
            setProgress(diagnostics)
            if (report.continuationRequired) {
                // WorkManager persists the retry request/backoff. No active polling or busy wait.
                Result.retry()
            } else {
                Result.success(diagnostics)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Source/article-local errors are persisted by their own state machines. Reaching here
            // means shared infrastructure (typically storage) was unavailable.
            if (runAttemptCount < MAX_INFRASTRUCTURE_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (error.message ?: error::class.java.simpleName)))
            }
        }
    }

    private fun diagnosticsData(report: CollectionPassReport) = workDataOf(
        KEY_SOURCE_SUCCESSES to report.collection.successes,
        KEY_SOURCE_FAILURES to report.collection.failures,
        KEY_SOURCE_SKIPPED to report.collection.skipped,
        KEY_DISCOVERED_ENTRIES to report.collection.discoveredEntries,
        KEY_ARTICLES_PROCESSED to report.ingestion.processed,
        KEY_ARTICLES_ADDED to report.ingestion.addedToInbox,
        KEY_ARTICLES_MERGED to report.ingestion.mergedIntoInbox,
        KEY_ARTICLES_KNOWN to report.ingestion.alreadyKnown,
        KEY_ARTICLE_FAILURES to report.ingestion.failed,
        KEY_ARTICLE_SKIPPED to report.ingestion.skipped,
        KEY_ARTICLE_STALE to report.ingestion.stale,
    )

    private fun isUnmeteredNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val KEY_SOURCE_SUCCESSES = "source_successes"
        const val KEY_SOURCE_FAILURES = "source_failures"
        const val KEY_SOURCE_SKIPPED = "source_skipped"
        const val KEY_DISCOVERED_ENTRIES = "discovered_entries"
        const val KEY_ARTICLES_PROCESSED = "articles_processed"
        const val KEY_ARTICLES_ADDED = "articles_added"
        const val KEY_ARTICLES_MERGED = "articles_merged"
        const val KEY_ARTICLES_KNOWN = "articles_known"
        const val KEY_ARTICLE_FAILURES = "article_failures"
        const val KEY_ARTICLE_SKIPPED = "article_skipped"
        const val KEY_ARTICLE_STALE = "article_stale"
        const val KEY_ERROR = "error"
        private const val MAX_INFRASTRUCTURE_RETRIES = 3
    }
}

object CollectionWorkScheduler {
    const val PERIODIC_WORK_NAME = "article-navigator-periodic-collection"
    const val IMMEDIATE_WORK_NAME = "article-navigator-immediate-collection"
    const val WORK_TAG = "article-navigator-collection"
    const val PERIODIC_INTERVAL_MINUTES = 15L

    internal fun periodicConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    internal fun immediateConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    internal fun periodicRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<CollectionWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(periodicConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
            .addTag(WORK_TAG)
            .build()

    internal fun immediateRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CollectionWorker>()
            .setConstraints(immediateConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
            .addTag(WORK_TAG)
            .build()

    fun ensurePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(),
        )
    }

    fun runNow(context: Context): UUID {
        val request = immediateRequest()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    fun cancelImmediate(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }
}
