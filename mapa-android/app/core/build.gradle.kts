import java.util.Properties

// Pure Kotlin, no Android: everything here is unit-tested on the JVM and shared by both apps.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":routine-engine"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
}

tasks.test {
    useJUnitPlatform()
    // Live AI checks run only with -PliveAi; the key comes from the git-ignored .env and is never printed.
    if (project.hasProperty("liveAi")) {
        val env = Properties().apply { rootProject.file(".env").takeIf { it.exists() }?.reader()?.use { load(it) } }
        env.getProperty("mapa.basetenKey")?.let { systemProperty("mapa.basetenKey", it.trim()) }
        outputs.upToDateWhen { false }
    }
}
