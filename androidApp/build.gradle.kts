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

// Android's versionCode is a single, strictly increasing integer, so MAJOR.MINOR.PATCH plus an
// optional SemVer pre-release label (`-alpha`, `-beta.2`, `-rc.1`, ...) is folded into one number:
//   MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + preReleaseOrdinal
// preReleaseOrdinal keeps every pre-release of a given MAJOR.MINOR.PATCH strictly below its final
// release's own code (99), so `v1.2.0-beta.1` and the eventual `v1.2.0` never collide:
//   -alpha       ->  0        -alpha.N (N in 1..29) -> 0 + N
//   -beta        -> 30        -beta.N  (N in 1..29) -> 30 + N
//   -rc          -> 60        -rc.N    (N in 1..29) -> 60 + N
//   (no suffix)  -> 99
// A bare label (no `.N`) gets its own reserved ordinal rather than defaulting to `.1`'s — SemVer
// itself orders `1.2.0-alpha` strictly before `1.2.0-alpha.1`, and the older `?: 1` fallback here
// folded both to the same versionCode, which Play rejects as a re-upload of an already-seen code.
// This replaces an older scheme that stripped the pre-release suffix entirely before folding,
// which made every pre-release of a version share its eventual final release's versionCode — Play
// rejects a re-upload at an already-used versionCode, so that scheme could never actually publish
// a pre-release to a test track. Safe to change freely: as of this change, nothing has been
// uploaded to Google Play yet under the old scheme.
private val PRE_RELEASE_LABEL = Regex("""^(alpha|beta|rc)(?:\.(\d+))?$""")

fun versionCodeOf(version: String): Int {
    val base = version.substringBefore('-')
    val suffix = version.substringAfter('-', missingDelimiterValue = "").ifEmpty { null }

    val parts = base.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    // MINOR/PATCH must fit the two decimal digits reserved for each, and MAJOR must stay below the
    // debugVersionCode ceiling below (1999 * 1_000_000 + 99 * 10_000 + 99 * 100 + 99 = 1_999_999_999
    // is the largest value this can produce), or two distinct versions could fold to the same code.
    require(major in 0..1999 && minor in 0..99 && patch in 0..99) {
        "versionCodeOf requires MAJOR in 0..1999 and MINOR/PATCH in 0..99, got $version"
    }

    val preReleaseOrdinal = if (suffix == null) {
        99
    } else {
        val match = PRE_RELEASE_LABEL.matchEntire(suffix)
            ?: error(
                "versionCodeOf does not recognize pre-release label '$suffix' in $version — " +
                    "expected alpha/beta/rc, optionally followed by a numeric ordinal (e.g. beta.1)",
            )
        // The `.N` group is empty (not merely absent) for a bare label like `alpha` — that case
        // gets its own reserved ordinal (0) rather than silently sharing `.1`'s, so `1.2.0-alpha`
        // and `1.2.0-alpha.1` never fold to the same versionCode. `toIntOrNull()` failing on a
        // non-empty group means the number overflowed Int — reject it explicitly rather than
        // falling back to a default that would hide the collision the same way.
        val ordinalGroup = match.groupValues[2]
        val ordinal = if (ordinalGroup.isEmpty()) {
            0
        } else {
            val explicitOrdinal = ordinalGroup.toIntOrNull()
                ?: error(
                    "versionCodeOf requires the pre-release ordinal to fit in a 32-bit integer, " +
                        "got '$ordinalGroup' in $version",
                )
            require(explicitOrdinal in 1..29) {
                "versionCodeOf requires the pre-release ordinal in 1..29, got $explicitOrdinal in $version"
            }
            explicitOrdinal
        }
        when (match.groupValues[1]) {
            "alpha" -> 0 + ordinal
            "beta" -> 30 + ordinal
            "rc" -> 60 + ordinal
            else -> error("unreachable: pre-release label already matched alpha|beta|rc")
        }
    }

    return major * 1_000_000 + minor * 10_000 + patch * 100 + preReleaseOrdinal
}

// CI regression guard for versionCodeOf (see ci.yml's "Verify Android versionCode scheme" step).
// A plain stdout probe rather than a unit test, since this function lives in a *.gradle.kts script
// rather than production Kotlin source and has no test source set of its own to live in.
// `-PversionCodeProbe` is a comma-separated list of version strings; unset/empty prints nothing.
tasks.register("printAndroidVersionCodes") {
    doLast {
        val probe = project.findProperty("versionCodeProbe") as? String
        probe?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { version ->
            println("$version=${versionCodeOf(version)}")
        }
    }
}

// Fixed versionCode for every debug variant — see the onVariants block at the bottom of this file
// for why. Below Play's 2_100_000_000 ceiling (a debug build is never uploaded, but staying inside
// the documented range keeps the value from looking arbitrary) and above anything versionCodeOf
// can fold to (see its own MAJOR-range comment above).
val debugVersionCode = 2_000_000_000

// -P > environment variable > local.properties, the repo-wide order local.properties.example's
// "Priority" header documents — so a `-PandroidRelease...` value can override an ANDROID_RELEASE_*
// one already exported into the shell (release.yml passes all four via the environment).
// Blank counts as unset: local.properties.example ships these keys with empty values, so a plain
// null check treats a freshly copied file as "configured" and then fails deep inside AGP with
// "Keystore file not set for signing config release" instead of taking the unsigned path below.
// Each source is tested individually rather than the elvis chain's result: a blank higher-priority
// source is non-null, so it would otherwise short-circuit the chain and mask a valid lower-priority
// value (GitHub Actions maps an undefined secret to "" rather than leaving the variable unset, and
// `-PandroidReleaseKeystorePath` with no value does the same). Same pattern, same reason, as
// composeApp/build.gradle.kts's resolvedUpdateRepo. Shared by both the app-signing and upload
// signing identities below (the `env`/`gradleProperty`/`localProperty` names differ per call).
fun releaseSigningValue(env: String, gradleProperty: String, localProperty: String): String? =
    (project.findProperty(gradleProperty) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localProperty)?.takeIf { it.isNotBlank() }

// The app signing identity: signs githubRelease directly (the APK attached to GitHub Releases) and
// is the fallback playRelease signing identity when no dedicated upload key is configured (see
// "upload" below). This is also the key enrolled with Google Play Console as the *app signing
// key* — the identity Google re-signs every APK with before it reaches a device — so what has to
// match across the GitHub and Play channels is that re-signed identity, not the key a given AAB
// happened to be uploaded with. See docs/build.md's "Publishing to Google Play".
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

// The upload signing identity: signs the playRelease variant (AAB included — see below), the one
// submitted to Google Play Console. Optional and independent of the app-signing values above —
// the same keystore MAY serve both roles, but this project registers a distinct upload key with
// Play, so a dedicated keystore signs playRelease rather than the app signing key. See
// docs/build.md's "Publishing to Google Play".
val uploadKeystorePath = releaseSigningValue("ANDROID_UPLOAD_KEYSTORE_PATH", "androidUploadKeystorePath", "android.upload.keystore.path")
val uploadKeystorePassword = releaseSigningValue("ANDROID_UPLOAD_KEYSTORE_PASSWORD", "androidUploadKeystorePassword", "android.upload.keystore.password")
val uploadKeyAlias = releaseSigningValue("ANDROID_UPLOAD_KEY_ALIAS", "androidUploadKeyAlias", "android.upload.key.alias")
val uploadKeyPassword = releaseSigningValue("ANDROID_UPLOAD_KEY_PASSWORD", "androidUploadKeyPassword", "android.upload.key.password")

val missingUploadSigningValues = buildList {
    if (uploadKeystorePath == null) add("keystore path")
    if (uploadKeystorePassword == null) add("keystore password")
    if (uploadKeyAlias == null) add("key alias")
    if (uploadKeyPassword == null) add("key password")
}

// Opt-in enforcement for anything that publishes an artifact (release.yml and publish-play.yml
// pass this). Without it, a missing or half-configured secret would fall through to the unsigned
// (app signing) / fallback-to-app-signing (upload) paths below, and the workflow would happily
// upload the result anyway. Deliberately -P only: unlike the eight values above this never
// arrives via CI secrets, it is a flag the workflow sets on the command line, the same way it
// already passes -PappVersion. Applies uniformly to both signing identities regardless of which
// flavor a given Gradle invocation actually builds — see the "upload" signingConfigs branch below.
val releaseSigningRequired =
    (project.findProperty("androidReleaseSigningRequired") as? String)?.toBooleanStrictOrNull() ?: false

// Bold yellow, so this one WARN line stands out among hundreds of ordinary task lines instead of
// looking identical to them. NO_COLOR (https://no-color.org) opts out for environments that don't
// want ANSI codes, e.g. a plain log file.
fun highlightWarning(message: String): String =
    if (System.getenv("NO_COLOR") != null) message else "\u001B[1;33m$message\u001B[0m"

// Captured here (rather than looked up again inside androidComponents below, which is a separate
// top-level DSL call and cannot see the android{} block's own `signingConfigs` receiver) so the
// variant-signing hookup at the bottom of this file can tell whether a dedicated upload key was
// actually configured. Assigned inside the android{} block below, before androidComponents{}'s
// onVariants callback ever runs.
var uploadSigningConfig: com.android.build.api.dsl.ApkSigningConfig? = null

android {
    namespace = "works.merc.keryx.app.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "works.merc.keryx"
        // Kept in lockstep with composeApp's androidLibrary minSdk (see
        // .claude/rules/android-sqlite-bundling.md for why 26).
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeOf(appVersion)
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

        // See the "upload" signingConfigs comment on the vals above and the androidComponents
        // block at the bottom of this file for how this actually gets attached to playRelease.
        when {
            uploadKeystorePath != null && uploadKeystorePassword != null && uploadKeyAlias != null && uploadKeyPassword != null ->
                uploadSigningConfig = create("upload") {
                    storeFile = File(uploadKeystorePath)
                    storePassword = uploadKeystorePassword
                    keyAlias = uploadKeyAlias
                    keyPassword = uploadKeyPassword
                }

            // Same reasoning as the app-signing block above: a half-configured upload identity is
            // always a mistake, not a partial setup to fall back from.
            missingUploadSigningValues.size < 4 ->
                error("Incomplete Android upload signing configuration: missing ${missingUploadSigningValues.joinToString()}. See docs/setup.md.")

            // Unlike the app-signing case, there is no unsigned fallback here to warn about:
            // leaving this unconfigured simply means playRelease signs with the app signing
            // config instead (see androidComponents below) — the same throwaway keystore then
            // covers github/play/debug for local development, with nothing left unsigned.
            releaseSigningRequired ->
                error("Android upload signing is required here but is not configured. See docs/build.md.")

            else -> {}
        }
    }

    buildTypes {
        release {
            // Deliberately never falls back to the debug signing config: a debug-signed release
            // artifact is installable and looks legitimate, which is exactly the dangerous case.
            // `null` here is AGP's own unsigned-release behavior instead — it fails closed, since
            // an unsigned APK can be neither installed nor published. Anything that actually
            // distributes sets `androidReleaseSigningRequired` so the unsigned path is a hard
            // error there (see the signingConfigs block above). playRelease is re-pointed at the
            // "upload" config instead, when one is configured, by the androidComponents block
            // below — this buildType-level assignment is what every *other* release variant
            // (namely githubRelease) actually uses.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Splits the one distribution-specific permission an in-app update install needs
    // (REQUEST_INSTALL_PACKAGES, declared only in src/github/AndroidManifest.xml) out of the AAB
    // submitted to Google Play, without maintaining two applicationIds — `composeApp` is a KMP
    // library module (no flavor dimension of its own) and, having none, is consumed identically by
    // both flavors, so no `missingDimensionStrategy` is needed on this side either. See
    // `docs/app-architecture.md`'s in-app-update section and `AndroidUpdateInstaller`'s own KDoc
    // for how `canInstallUpdates` reads the *merged manifest* at runtime rather than branching on
    // the flavor name — a play-flavored APK can still reach this code path if sideloaded outside
    // Play (Play Console test tracks, `bundletool`, internal distribution), so the runtime check
    // must stay independent of which flavor produced the APK.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }
}

// playDebug builds and installs like any other debug variant, but nobody has a reason to run it:
// Play Debug is never uploaded (only playRelease is), never sideloaded for manual testing (that's
// what githubDebug is for), and the flavors differ only in the REQUEST_INSTALL_PACKAGES manifest
// permission (see the flavorDimensions comment above) — nothing debug-build-specific to exercise
// there that githubDebug doesn't already cover. Disabling it keeps `./gradlew assembleDebug` and
// `connectedAndroidTest` (see docs/testing.md) from building/running a variant nobody uses, down to
// githubDebug/githubRelease/playRelease.
androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "play").withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
    }

    // The playRelease variant as a whole (not just the AAB `bundlePlayRelease` produces — any
    // `assemblePlayRelease` APK carries this too) signs with the dedicated upload key instead of
    // the app signing key githubRelease uses, whenever one is configured (see the signingConfigs
    // block above). Done through the variant API rather than a
    // flavor-scoped `signingConfig` assignment in the DSL so it stays independent of AGP's own
    // buildType-vs-flavor signingConfig precedence rules (a buildType-level assignment always
    // wins), and so buildTypes.release's existing signingConfig assignment above — which every
    // other release variant relies on — needs no change at all.
    onVariants(selector().withFlavor("distribution" to "play").withBuildType("release")) { variant ->
        uploadSigningConfig?.let { upload -> variant.signingConfig.from(upload) }
    }

    // A debug install must never be rejected as a downgrade. A local build passes no -PappVersion,
    // so appVersion falls back to "0.0.0" and folds to a low versionCode — lower than any
    // real-version APK already on the device (e.g. a release build sideloaded to exercise the
    // in-app update flow), and the package manager refuses that with
    // INSTALL_FAILED_VERSION_DOWNGRADE before it looks at anything else. Debug builds are never
    // published, so their versionCode only has to satisfy the package manager: pinning it above
    // every code versionCodeOf can produce makes `installGithubDebug` work whatever is installed.
    // `versionName` is deliberately left alone — the About screen and the update check read
    // BuildConfig.VERSION (composeApp's, from the same appVersion), and 0.0.0 is what makes a dev
    // build see every release as an update. This does not make the reverse direction any harder
    // either: a release-signed APK and a debug one carry different signing keys, so swapping
    // between them needs an uninstall regardless.
    // The androidTest APK is unaffected: it is its own package (`works.merc.keryx.test`), so it
    // never competes with an installed build.
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set(debugVersionCode) }
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
