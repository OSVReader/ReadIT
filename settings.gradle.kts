
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
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        // Needed for Fuel 1.15.0 (required by r2-shared-kotlin via FolioReader)
        //maven { url = uri("https://repo.spring.io/libs-release") }
    }
}


rootProject.name = "booklibrary"
include(":app")
