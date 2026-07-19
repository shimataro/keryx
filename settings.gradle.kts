rootProject.name = "keryx"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // jSystemThemeDetector / java-keyring are published on JitPack.
        maven("https://jitpack.io")
    }
}

plugins {
    // Auto-provisions the JDK toolchain (JDK 25) when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
