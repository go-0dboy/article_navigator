plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.go0dboy.articlenavigator.scheduler.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":scheduler:core"))
    implementation(libs.androidx.work.runtime.ktx)
}
