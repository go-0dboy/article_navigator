plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "io.github.go0dboy.articlenavigator.storage.database"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Android host unit tests run on the desktop JVM. The Android variant of
// sqlite-bundled does not carry the host native library, so only the unit-test
// runtime classpath is substituted with the JVM variant. Production Android
// continues to use the normal multiplatform sqlite-bundled dependency.
configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        resolutionStrategy.dependencySubstitution {
            substitute(module("androidx.sqlite:sqlite-bundled:${libs.versions.sqlite.get()}"))
                .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqlite.get()}"))
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    ksp(libs.androidx.room3.compiler)

    testImplementation(project(":scheduler:core"))
    testImplementation(project(":collector:api"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room3.testing)
}
