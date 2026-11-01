pluginManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/"); maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") } }
rootProject.name = "mapa-typecheck"
include(":core", ":routine-engine")
project(":core").projectDir = file("../../core")
project(":routine-engine").projectDir = file("../../../routine-engine/engine")
