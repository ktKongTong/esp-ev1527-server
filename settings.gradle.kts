rootProject.name = "esp32-control"

// local.properties is gitignored and is the existing place for machine-local
// credentials. CI uses the GITHUB_ACTOR / GITHUB_TOKEN environment variables.
val localProperties = java.util.Properties().apply {
    val file = file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun localCredential(name: String): String? =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)

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
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/ktkongtong/ktor-mqtt")
            credentials {
                username = localCredential("gpr.user") ?: System.getenv("GITHUB_ACTOR")
                password = localCredential("gpr.key") ?: System.getenv("GITHUB_TOKEN")
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":contract")
include(":server")
include(":shared")
include(":androidApp")
include(":webApp")
