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
include(":collector:api")
include(":pipeline")
include(":storage:database")
