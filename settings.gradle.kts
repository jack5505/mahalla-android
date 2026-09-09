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
        google()
        mavenCentral()
    }
}

rootProject.name = "Mahalla"
include(":app")
// Снятие Baseline Profile и замер холодного старта (эпик 13.3). Модуль не
// попадает в APK и требует устройства — в CI не собирается ничем, кроме
// компиляции.
include(":baselineprofile")
