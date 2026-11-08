pluginManagement {
    repositories {
        // CI can prefetch this verified official artifact when its web redirect is unavailable.
        providers.environmentVariable("SMARTPEN_CI_MAVEN").orNull?.let { repositoryPath ->
            maven {
                url = uri(repositoryPath)
                content { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        providers.environmentVariable("SMARTPEN_CI_MAVEN").orNull?.let { repositoryPath ->
            maven {
                url = uri(repositoryPath)
                content { includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable") }
            }
        }
        google()
        mavenCentral()
        // Legacy Xposed API 82 is only published on api.xposed.info.
        maven {
            url = uri("https://api.xposed.info/")
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "XiaomiSmartPenPlus"
include(":app")
