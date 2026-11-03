import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Real Firebase project only when its config file is present; otherwise the Emulator Suite (see README).
// -Pmapa.backend=emulator keeps a build on the local Emulator Suite even when the real config is present (demos, tests).
if (file("google-services.json").exists() && project.findProperty("mapa.backend") != "emulator") apply(plugin = "com.google.gms.google-services")

// Meta API keys for local testing only: put them in local.properties (git-ignored) or ~/.gradle/gradle.properties.
// A key inside an APK can be extracted, so a release build has none and calls the chat Cloud Function.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
    // .env (git-ignored) may hold the same keys, as key=value lines.
    rootProject.file(".env").takeIf { it.exists() }?.reader()?.use { load(it) }
}
fun secret(name: String): String = ((project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: "").trim()

android {
    namespace = "app.mapa.family"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mapa.family"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartext"] = "true"
            buildConfigField("String", "MUSE_SPARK_KEY", "\"${secret("mapa.museSparkKey")}\"")
            buildConfigField("String", "BASETEN_KEY", "\"${secret("mapa.basetenKey")}\"")
        }
        release {
            manifestPlaceholders["cleartext"] = "false"
            // Never a key in a release APK: the chat Cloud Function holds them.
            buildConfigField("String", "MUSE_SPARK_KEY", "\"\"")
            buildConfigField("String", "BASETEN_KEY", "\"\"")
            // R8 shrinks code and unused resources; Play also serves only the phone's own CPU libraries.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
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
    implementation(project(":shared"))
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // QR code for setting up a parent's phone in person.
    implementation("com.google.zxing:core:3.5.3")
    // "Continue with Google": Android Credential Manager with a Google ID token, then Firebase Auth.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Gemini Nano (on-device, supplied by Android on supported phones) writes the daily note.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
