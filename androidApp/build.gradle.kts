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

// Blank counts as unset: local.properties.example ships these keys with empty values, so a plain
// null check treats a freshly copied file as "configured" and then fails deep inside AGP with
// "Keystore file not set for signing config release" instead of taking the unsigned path below.
// Each source is tested individually rather than the elvis chain's result: a blank higher-priority
// source is non-null, so it would otherwise short-circuit the chain and mask a valid lower-priority
// value (GitHub Actions maps an undefined secret to "" rather than leaving the variable unset, and
// `-PandroidReleaseKeystorePath` with no value does the same). Same pattern, same reason, as
// composeApp/build.gradle.kts's resolvedUpdateRepo.
fun releaseSigningValue(env: String, gradleProperty: String, localProperty: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(gradleProperty) as? String)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localProperty)?.takeIf { it.isNotBlank() }

val keystorePath = releaseSigningValue("ANDROID_RELEASE_KEYSTORE_PATH", "androidReleaseKeystorePath", "android.release.keystore.path")
val keystorePassword = releaseSigningValue("ANDROID_RELEASE_KEYSTORE_PASSWORD", "androidReleaseKeystorePassword", "android.release.keystore.password")
// Named releaseKeyAlias/releaseKeyPassword rather than keyAlias/keyPassword: inside the
// `create("release") { ... }` block below, ApkSigningConfig itself declares properties of
// exactly those names, and an unqualified reference on the right-hand side of an assignment
// there resolves to the receiver's own (still-null) property, not this outer val - a real bug
// caught by manually verifying this file (`this.keyPassword = keyPassword` silently assigned
// null to itself, and packageRelease then failed with "missing required property keyPassword").
val releaseKeyAlias = releaseSigningValue("ANDROID_RELEASE_KEY_ALIAS", "androidReleaseKeyAlias", "android.release.key.alias")
val releaseKeyPassword = releaseSigningValue("ANDROID_RELEASE_KEY_PASSWORD", "androidReleaseKeyPassword", "android.release.key.password")

val missingSigningValues = buildList {
    if (keystorePath == null) add("keystore path")
    if (keystorePassword == null) add("keystore password")
    if (releaseKeyAlias == null) add("key alias")
    if (releaseKeyPassword == null) add("key password")
}

// Opt-in enforcement for anything that publishes an artifact (release.yml passes this). Without
// it, a missing or half-configured secret there would fall through to the unsigned path below,
// and release.yml's own `find ... -name '*.apk'` would happily upload the result. Deliberately
// -P only: unlike the four values above this never arrives via CI secrets, it is a flag the
// workflow sets on the command line, the same way it already passes -PappVersion.
val releaseSigningRequired =
    (project.findProperty("androidReleaseSigningRequired") as? String)?.toBooleanStrictOrNull() ?: false

// Bold yellow, so this one WARN line stands out among hundreds of ordinary task lines instead of
// looking identical to them. NO_COLOR (https://no-color.org) opts out for environments that don't
// want ANSI codes, e.g. a plain log file.
fun highlightWarning(message: String): String =
    if (System.getenv("NO_COLOR") != null) message else "\u001B[1;33m$message\u001B[0m"

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
        when {
            // Spelled out rather than `missingSigningValues.isEmpty()` so the four vals above
            // smart-cast to non-null inside this branch.
            keystorePath != null && keystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null ->
                create("release") {
                    storeFile = File(keystorePath)
                    storePassword = keystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }

            // Half-configured is always a mistake, never "not set up yet" — say which values are
            // missing rather than letting AGP fail with a generic message, or silently going
            // unsigned as if nothing were configured at all.
            missingSigningValues.size < 4 ->
                error("Incomplete Android release signing configuration: missing ${missingSigningValues.joinToString()}. See docs/setup.md.")

            releaseSigningRequired ->
                error("Android release signing is required here but is not configured. See docs/build.md.")

            else -> project.logger.warn(
                highlightWarning(
                    "⚠ No Android release signing configured — :androidApp's release build will be UNSIGNED " +
                        "(it cannot be installed on a device or uploaded to Google Play). This keeps plain " +
                        "`./gradlew build` working for desktop-only work; see docs/setup.md to configure signing.",
                ),
            )
        }
    }

    buildTypes {
        release {
            // Deliberately never falls back to the debug signing config: a debug-signed release
            // artifact is installable and looks legitimate, which is exactly the dangerous case.
            // `null` here is AGP's own unsigned-release behavior instead — it fails closed, since
            // an unsigned APK can be neither installed nor published. Anything that actually
            // distributes sets `androidReleaseSigningRequired` so the unsigned path is a hard
            // error there (see the signingConfigs block above).
            signingConfig = signingConfigs.findByName("release")
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
