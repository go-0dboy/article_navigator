package io.github.go0dboy.articlenavigator

import android.app.Application
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkScheduler
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencies
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArticleNavigatorApplication : Application(), CollectionWorkerDependencyProvider {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val collectionWorkerDependencies: CollectionWorkerDependencies
        get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        CollectionWorkScheduler.ensurePeriodic(applicationContext)
        applicationScope.launch {
            container.ensureSampleSource()
        }
    }
}
