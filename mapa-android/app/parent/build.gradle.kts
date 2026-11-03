plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Real Firebase project only when its config file is present; otherwise the Emulator Suite (see README).
// -Pmapa.backend=emulator keeps a build on the local Emulator Suite even when the real config is present (demos, tests).
if (file("google-services.json").exists() && project.findProperty("mapa.backend") != "emulator") apply(plugin = "com.google.gms.google-services")

// The trained rhythm models from the routine-engine workstream, bundled as assets: the CNN (weights for the
// engine and the prior, plus the ExecuTorch program) and the earlier GRU as a last-resort fallback.
// Size of the CNN to ship: xs, s, m or l (see routine-engine/models/cnn/cnn_summary.json).
val cnnSize = (project.findProperty("mapa.cnnSize") as String?) ?: "s"
val rhythmAssets = layout.buildDirectory.dir("generated/rhythmAssets")
val hybridBenchmarkAssets = layout.buildDirectory.dir("generated/hybridBenchmarkAssets")
val copyRhythmModel by tasks.registering(Copy::class) {
    from(rootProject.file("../routine-engine/models")) { include("rhythm_v2.weights.txt") }
    from(rootProject.file("../routine-engine/models/cnn")) {
        include("rhythm_cnn_$cnnSize.weights.txt", "rhythm_cnn_$cnnSize.pte")
        rename("rhythm_cnn_$cnnSize\\.(.*)", "rhythm_cnn.$1")
    }
    // The trained MaPa Rhythm Hybrid (final-v2, tested once): Kotlin weights, archetype priors, two ExecuTorch programs.
    from(rootProject.file("../routine-engine/models/hybrid")) {
        include("hybrid_reference.weights.txt", "hybrid_priors.txt", "rhythm-step.pte", "rhythm-daily.pte")
    }
    into(rhythmAssets.map { it.dir("models") })
}
val copyHybridBenchmarkModel by tasks.registering(Copy::class) {
    from(rootProject.file("../routine-engine/models/hybrid")) {
        include("hybrid_reference.weights.txt", "rhythm-step.pte", "rhythm-daily.pte")
    }
    into(hybridBenchmarkAssets)
}

android {
    // ExecuTorch maps the program file; keep .pte uncompressed in the APK (a compressed asset has no file descriptor).
    androidResources { noCompress += "pte" }
    namespace = "app.mapa.parent"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mapa.parent"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Optional: where to download the on-device note model (.task). Empty = adb push only (see README).
    }

    buildTypes {
        debug {
            // Cleartext only so the debug build can reach the local Firebase Emulator Suite.
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            manifestPlaceholders["cleartext"] = "false"
            // R8 shrinks code and unused resources; Play also serves only the phone's own CPU libraries.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    sourceSets["main"].assets.srcDir(rhythmAssets)
    sourceSets["androidTest"].assets.srcDir(hybridBenchmarkAssets)
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild") { dependsOn(copyRhythmModel, copyHybridBenchmarkModel) }

dependencies {
    implementation(project(":shared"))
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Activity Recognition: the movement signal (times only, no location).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Meta's ExecuTorch runs the rhythm CNN (arm64-v8a and x86_64 libraries; same version as the exporter).
    implementation("org.pytorch:executorch-android:1.5.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
