pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "article-navigator"

include(":app")
include(":core:model")
include(":core:data")
include(":core:network")
include(":collector:api")
include(":collector:rss")
include(":pipeline")
include(":scheduler:core")
include(":scheduler:android")
include(":storage:database")
include(":feature:sources")
include(":feature:inbox")
include(":feature:library")
