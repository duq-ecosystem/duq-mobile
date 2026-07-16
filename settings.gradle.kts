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
        maven { url = uri("https://jitpack.io") }
        // Официальный native Telegram Login SDK (org.telegram:login-sdk) — только GitHub Packages.
        // Креды: gpr.user/gpr.key из ~/.gradle/gradle.properties ЛИБО env GITHUB_USERNAME/GITHUB_TOKEN
        // (в CI — из секрета). PAT нужен со scope read:packages.
        maven {
            url = uri("https://maven.pkg.github.com/TelegramMessenger/telegram-login-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "DuqMobile"
include(":shared")
include(":androidApp")
