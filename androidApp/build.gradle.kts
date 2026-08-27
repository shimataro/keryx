import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Same resolution order as composeApp/build.gradle.kts's appVersion (kept in this module too,
// duplicated rather than shared, since there is no buildSrc/convention-plugin setup yet — revisit
// if a third module ever needs the same logic). Only versionName/versionCode need it here;
// androidApp has no OAuth-client-key BuildConfig of its own (composeApp's is what the app reads).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersion: String =
    (project.findProperty("appVersion") as String?)
        ?: System.getenv("APP_VERSION")
        ?: "0.0.0"

// See composeApp/build.gradle.kts's androidVersionCode for the folding scheme (1.2.3 -> 10203).
val androidVersionCode: Int = appVersion.substringBefore('-').split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        // Each component must fit the two decimal digits reserved for it, or two distinct
        // versions could fold to the same versionCode (e.g. 1.100.0 and 2.0.0 both -> 20000).
        require(minor in 0..99 && patch in 0..99) {
            "androidVersionCode encoding requires MINOR and PATCH in 0..99, got $appVersion"
        }
        major * 10000 + minor * 100 + patch
    }
    .coerceAtLeast(1)

android {
    namespace = "works.merc.keryx.app.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "works.merc.keryx"
        // Kept in lockstep with composeApp's androidLibrary minSdk (see
        // .claude/rules/android-sqlite-bundling.md for why 26).
        minSdk = 26
        targetSdk = 37
        versionCode = androidVersionCode
        versionName = appVersion
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_RELEASE_KEYSTORE_PATH")
                ?: (project.findProperty("androidReleaseKeystorePath") as? String)
                ?: localProperties.getProperty("android.release.keystore.path")
            val keystorePassword = System.getenv("ANDROID_RELEASE_KEYSTORE_PASSWORD")
                ?: (project.findProperty("androidReleaseKeystorePassword") as? String)
                ?: localProperties.getProperty("android.release.keystore.password")
            val keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
                ?: (project.findProperty("androidReleaseKeyAlias") as? String)
                ?: localProperties.getProperty("android.release.key.alias")
            val keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
                ?: (project.findProperty("androidReleaseKeyPassword") as? String)
                ?: localProperties.getProperty("android.release.key.password")

            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = File(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            // Do NOT fall back to the debug signing config. If the release signing
            // properties are missing, AGP fails during validateSigningRelease instead
            // of silently producing a debug-signed (or unsigned) release artifact.
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":composeApp"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    // KeryxApplication.kt talks to Koin/Ktor/coroutines types directly (starting Koin, resolving
    // the shared HttpClient/CoroutineScope/FtsManager) — composeApp's own dependency on these is
    // `implementation`-scoped in its Gradle module, so it isn't exposed transitively here.
    implementation(libs.koin.core)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.coroutines.core)

    // Instrumented Compose UI tests for androidApp. These are not inherited from composeApp's
    // androidDeviceTest dependencies (test-scoped dependencies do not propagate across modules),
    // so they must be declared explicitly here.
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.runner.app.android.test)
    androidTestImplementation(libs.androidx.test.junit.app.android.test)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
