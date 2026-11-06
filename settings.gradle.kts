pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ivynote-android"

// Pure Kotlin: API client, sync engine, shelf, libraries. Tested on the JVM.
include(":core")
// The Android app. `-Pivy.coreOnly=true` leaves it out, for machines without
// the Android SDK (the core tests need only a JDK).
if (providers.gradleProperty("ivy.coreOnly").orNull != "true") {
    include(":app")
}
