plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mapa.telemetry"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mapa.telemetry.spike"
        // Android 7.0+. Covers Android Go handsets (8.1) used as the floor test.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Deliberately tiny: WorkManager for the 15-minute heartbeat, core for FileProvider/receiver flags.
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("androidx.core:core:1.13.1")
}
