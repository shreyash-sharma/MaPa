plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.mapa.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Firebase Emulator Suite host for builds without google-services.json (see README).
        val emulatorHost = (project.findProperty("mapa.emulatorHost") as String?) ?: "10.0.2.2"
        buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))

    api(platform("com.google.firebase:firebase-bom:33.7.0"))
    api("com.google.firebase:firebase-auth")
    api("com.google.firebase:firebase-firestore")
    api("com.google.firebase:firebase-messaging")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    api(platform("androidx.compose:compose-bom:2024.12.01"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-core")
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    api("androidx.core:core-ktx:1.15.0")
    debugApi("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
