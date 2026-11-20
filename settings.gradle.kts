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
rootProject.name = "ChessOverlay"
include(":core")
// CORE_ONLY: just the plain-Kotlin module (e.g. on a CI machine without the Android SDK).
if (System.getenv("CORE_ONLY") == null) include(":app")
