package io.github.go0dboy.articlenavigator.scheduler.android

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.go0dboy.articlenavigator.scheduler.core.CollectionRunReport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CollectionWorkTestApplication::class)
class CollectionWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var application: CollectionWorkTestApplication

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication() as CollectionWorkTestApplication
        context = application
        application.dependencies = successfulDependencies()
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun periodicWorkRequiresNetworkAndHealthyBattery() {
        val constraints = CollectionWorkScheduler.periodicRequest().workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test
    fun immediateWorkRequiresNetworkButDoesNotRequireHealthyBattery() {
        val constraints = CollectionWorkScheduler.immediateRequest().workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun ensurePeriodicRegistersOneUniquePeriodicWork() {
        CollectionWorkScheduler.ensurePeriodic(context)
        CollectionWorkScheduler.ensurePeriodic(context)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CollectionWorkScheduler.PERIODIC_WORK_NAME)
            .get()

        assertEquals(1, workInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, workInfos.single().state)
    }

    @Test
    fun runNowReplacesPreviousImmediateWork() {
        val firstId = CollectionWorkScheduler.runNow(context)
        val secondId = CollectionWorkScheduler.runNow(context)

        assertNotEquals(firstId, secondId)
        val manager = WorkManager.getInstance(context)
        val firstInfo = manager.getWorkInfoById(firstId).get()
        val secondInfo = checkNotNull(manager.getWorkInfoById(secondId).get())

        // REPLACE cancels and may immediately prune the predecessor from WorkManager's DB.
        // Either terminal representation proves the first request is no longer runnable.
        assertTrue(firstInfo == null || firstInfo.state == WorkInfo.State.CANCELLED)
        assertEquals(WorkInfo.State.ENQUEUED, secondInfo.state)
        val current = manager.getWorkInfosForUniqueWork(CollectionWorkScheduler.IMMEDIATE_WORK_NAME).get()
        assertEquals(1, current.size)
        assertEquals(secondId, current.single().id)
    }

    @Test
    fun cancelImmediateCancelsCurrentUniqueWork() {
        val id = CollectionWorkScheduler.runNow(context)

        CollectionWorkScheduler.cancelImmediate(context)

        val info = checkNotNull(WorkManager.getInstance(context).getWorkInfoById(id).get())
        assertEquals(WorkInfo.State.CANCELLED, info.state)
    }

    @Test
    fun sharedInfrastructureFailureRequestsWorkManagerRetry() = runTest {
        application.dependencies = CollectionWorkerDependencies { throw IllegalStateException("database unavailable") }
        val worker = TestListenableWorkerBuilder<CollectionWorker>(context)
            .setRunAttemptCount(0)
            .build()

        val result = worker.doWork()

        assertTrue("First shared-infrastructure failure must request retry: $result", result.toString().contains("Retry"))
    }

    @Test
    fun exhaustedSharedPassBudgetRequestsPersistedRetry() = runTest {
        application.dependencies = CollectionWorkerDependencies {
            CollectionPassReport(
                collection = CollectionRunReport(emptyList(), budgetExhausted = true),
                ingestion = IngestionRunDiagnostics(
                    processed = 0,
                    addedToInbox = 0,
                    mergedIntoInbox = 0,
                    alreadyKnown = 0,
                    failed = 0,
                    skipped = 0,
                    stale = 0,
                    budgetExhausted = false,
                ),
            )
        }
        val worker = TestListenableWorkerBuilder<CollectionWorker>(context).build()

        val result = worker.doWork()

        assertTrue("Budget continuation must be persisted as WorkManager retry: $result", result.toString().contains("Retry"))
    }

    private fun successfulDependencies() = CollectionWorkerDependencies {
        CollectionPassReport(
            collection = CollectionRunReport(emptyList()),
            ingestion = IngestionRunDiagnostics(
                processed = 0,
                addedToInbox = 0,
                mergedIntoInbox = 0,
                alreadyKnown = 0,
                failed = 0,
                skipped = 0,
                stale = 0,
                budgetExhausted = false,
            ),
        )
    }
}

class CollectionWorkTestApplication : Application(), CollectionWorkerDependencyProvider {
    lateinit var dependencies: CollectionWorkerDependencies
    override val collectionWorkerDependencies: CollectionWorkerDependencies
        get() = dependencies
}
