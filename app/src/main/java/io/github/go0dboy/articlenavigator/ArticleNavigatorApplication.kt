package io.github.go0dboy.articlenavigator

import android.app.Application
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkScheduler
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencies
import io.github.go0dboy.articlenavigator.scheduler.android.CollectionWorkerDependencyProvider

class ArticleNavigatorApplication : Application(), CollectionWorkerDependencyProvider {
    lateinit var container: AppContainer
        private set

    override val collectionWorkerDependencies: CollectionWorkerDependencies
        get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        CollectionWorkScheduler.ensurePeriodic(applicationContext)
    }
}
