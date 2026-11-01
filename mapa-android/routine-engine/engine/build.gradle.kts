// Pure Kotlin, no Android dependencies, so the parent app module can depend on it directly.
plugins {
    kotlin("jvm")
}

// Bytecode targets JVM 17 to match the Android app module.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test {
    useJUnitPlatform()
    // Golden vectors and weights produced by the Python side.
    systemProperty("mapa.models", providers.gradleProperty("mapaModels")
        .orElse(rootProject.file("models").absolutePath).get())
}
