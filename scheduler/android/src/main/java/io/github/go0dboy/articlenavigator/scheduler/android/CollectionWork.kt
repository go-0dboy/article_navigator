package io.github.go0dboy.articlenavigator.scheduler.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunReport
import java.util.UUID
import java.util.concurrent.TimeUnit

fun interface CollectionWorkerDependencies {
    suspend fun runCollection(isUnmeteredNetwork: Boolean): CollectionRunReport
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
            Result.success(
                workDataOf(
                    KEY_SUCCESSES to report.successes,
                    KEY_FAILURES to report.failures,
                    KEY_SKIPPED to report.skipped,
                ),
            )
        } catch (error: Throwable) {
            if (runAttemptCount < MAX_INFRASTRUCTURE_RETRIES) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(KEY_ERROR to (error.message ?: error::class.java.simpleName)),
                )
            }
        }
    }

    private fun isUnmeteredNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val KEY_SUCCESSES = "successes"
        const val KEY_FAILURES = "failures"
        const val KEY_SKIPPED = "skipped"
        const val KEY_ERROR = "error"
        private const val MAX_INFRASTRUCTURE_RETRIES = 3
    }
}

object CollectionWorkScheduler {
    const val PERIODIC_WORK_NAME = "article-navigator-periodic-collection"
    const val IMMEDIATE_WORK_NAME = "article-navigator-immediate-collection"
    const val WORK_TAG = "article-navigator-collection"

    private val connectedConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CollectionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connectedConstraint)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun runNow(context: Context): UUID {
        val request = OneTimeWorkRequestBuilder<CollectionWorker>()
            .setConstraints(connectedConstraint)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }
}
