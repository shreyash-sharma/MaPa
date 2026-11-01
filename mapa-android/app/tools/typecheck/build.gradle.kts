// Sandbox-only type check of the Android modules: android-all (framework API) + Compose Desktop
// (same androidx.compose packages) + hand stubs for Firebase, WorkManager, MediaPipe, activity.
plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets.main {
    kotlin.srcDirs("stubs", "../../shared/src/main/kotlin", "../../parent/src/main/kotlin", "../../family/src/main/kotlin")
}
dependencies {
    implementation(project(":core"))
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.1")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.7.1")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.7.1")
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.7.1")
    implementation("org.jetbrains.compose.material:material-icons-core-desktop:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
