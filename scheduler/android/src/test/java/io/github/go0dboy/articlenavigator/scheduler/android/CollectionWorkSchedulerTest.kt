package io.github.go0dboy.articlenavigator.scheduler.android

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CollectionWorkSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    @Test
    fun periodicWorkRequiresNetworkAndHealthyBattery() {
        val constraints = CollectionWorkScheduler.periodicConstraints()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow)
    }

    @Test
    fun immediateWorkRequiresNetworkButDoesNotBlockManualRunOnBatteryLevel() {
        val constraints = CollectionWorkScheduler.immediateConstraints()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresBatteryNotLow)
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
}
