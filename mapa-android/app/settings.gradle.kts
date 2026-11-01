pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mapa"

include(":core", ":shared", ":parent", ":family")

// The rhythm engine belongs to the routine-engine workstream. It is consumed here as a
// module from the sibling folder and never edited from this project.
include(":routine-engine")
project(":routine-engine").projectDir = file("../routine-engine/engine")
