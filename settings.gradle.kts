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

// The UI-framework-free shared code (core/data/domain + SQLDelight schema) — see
// shared/build.gradle.kts.
include(":shared")
include(":composeApp")
// Test-only helpers used by both :shared's and :composeApp's tests.
include(":testing")
// The Android application module (AndroidManifest, MainActivity, KeryxApplication). Separate
// from :composeApp because AGP 9's com.android.application plugin cannot coexist with the Kotlin
// Multiplatform plugin in the same module — see the plan doc's "実装中に判明した構造変更".
include(":androidApp")
// Everything that depends on Google Play services (Google Drive on Android, via AuthorizationClient),
// kept out of :shared/:composeApp so the F-Droid flavor can leave it out entirely — see
// androidGms/build.gradle.kts.
include(":androidGms")
