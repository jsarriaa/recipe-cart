pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "CocinaParaLechugones"

include(":app")
include(":core:domain")
include(":core:data")
include(":provider:api")
include(":provider:mercadona")
include(":provider:dia")
include(":provider:carrefour")
include(":provider:aldi")
include(":provider:bonarea")
include(":provider:ahorramas")
include(":provider:consum")
