plugins {
    kotlin("jvm")
    application
}

// Bytecode targets JVM 17 to match the Android app module.
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":engine"))
}

application {
    mainClass.set("app.mapa.routine.harness.HarnessKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
