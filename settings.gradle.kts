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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "My Application"
include(":app")
include(":baselineprofile")
// Match the app's opt-in: importing an unused native module still runs CMake
// during IDE sync, which fails without the external FFmpeg source/build trees.
val localBuildProperties = java.util.Properties().apply {
    val propertiesFile = file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { load(it) }
    }
}
val localFfmpegOption = providers.gradleProperty("useLocalFfmpegDecoder").orNull
    ?: providers.environmentVariable("USE_LOCAL_FFMPEG_DECODER").orNull
    ?: localBuildProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
if (listOf("1", "true", "yes").any { it.equals(localFfmpegOption, ignoreCase = true) }) {
    include(":ffmpeg-decoder-downmix")
}
