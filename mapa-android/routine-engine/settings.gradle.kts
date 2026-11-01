pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "mapa-routine-engine"
include(":engine", ":harness")
