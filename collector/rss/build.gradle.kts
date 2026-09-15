plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":collector:api"))
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
