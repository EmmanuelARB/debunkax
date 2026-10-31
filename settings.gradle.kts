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
        // Publishes the sherpa-onnx Android AAR (fallback speech engine); see AGENTS.md.
        maven("https://jitpack.io")
    }
}

rootProject.name = "debunkax"

include(":app")
