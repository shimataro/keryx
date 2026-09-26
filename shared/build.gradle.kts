import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Properties

// The UI-framework-free half of the app: core, data, domain, the SQLDelight schema, and the
// platform abstractions those need. :composeApp (the Compose UI, for desktop and Android) depends
// on it, and a native Apple app will consume it as a framework — so nothing here may reference
// Compose, Compose Resources, AWT/Swing or an Android UI API. See docs/app-architecture.md's
// "Apple Native Apps (SwiftUI)".
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // No version here: the root project already puts AGP on the build classpath.
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    // Shapes the Apple framework's Swift API; no effect on the JVM/Android artifacts.
    alias(libs.plugins.skie)
}

// --- Resolve DROPBOX_APP_KEY: -PdropboxAppKey > env var > local.properties > empty ---
// An empty key hides the Dropbox option from the UI entirely (see CloudStorageAvailability).
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val resolvedDropboxAppKey: String =
    (project.findProperty("dropboxAppKey") as String?)
        ?: System.getenv("DROPBOX_APP_KEY")
        ?: localProperties.getProperty("dropbox.app.key")
        ?: ""

// --- Resolve ONEDRIVE_CLIENT_ID: -PoneDriveClientId > env var > local.properties > empty ---
// An empty id hides the OneDrive option from the UI entirely (see CloudStorageAvailability).
// OneDrive is a PKCE public client (native/desktop), so no client secret is required.
val resolvedOneDriveClientId: String =
    (project.findProperty("oneDriveClientId") as String?)
        ?: System.getenv("ONEDRIVE_CLIENT_ID")
        ?: localProperties.getProperty("onedrive.client.id")
        ?: ""

// --- Resolve GOOGLE_DRIVE_CLIENT_ID: -PgoogleDriveClientId > env var > local.properties > empty ---
// An empty id hides the Google Drive option from the UI entirely (see CloudStorageAvailability).
val resolvedGoogleDriveClientId: String =
    (project.findProperty("googleDriveClientId") as String?)
        ?: System.getenv("GOOGLE_DRIVE_CLIENT_ID")
        ?: localProperties.getProperty("googledrive.client.id")
        ?: ""

// --- Resolve GOOGLE_DRIVE_CLIENT_SECRET: -PgoogleDriveClientSecret > env var > local.properties > empty ---
// Google's token endpoint requires this for "Desktop app" clients even with PKCE.
val resolvedGoogleDriveClientSecret: String =
    (project.findProperty("googleDriveClientSecret") as String?)
        ?: System.getenv("GOOGLE_DRIVE_CLIENT_SECRET")
        ?: localProperties.getProperty("googledrive.client.secret")
        ?: ""

// --- Resolve UPDATE_REPO: -PupdateRepo > env var > local.properties > default ---
// GitHub "owner/repo" slug the update checker polls via the public releases/latest API.
// Unlike the secrets above, an empty value here is not a meaningful "disabled" state —
// it would produce `repos//releases/latest` and make every update check fail — so blank values
// fall through to the next candidate instead of being taken literally.
val resolvedUpdateRepo: String =
    (project.findProperty("updateRepo") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("UPDATE_REPO")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("update.repo")?.takeIf { it.isNotBlank() }
        ?: "shimataro/keryx"

// --- Resolve the app version: -PappVersion > env var > the literal below ---
// Same resolution order as composeApp/build.gradle.kts's appVersion (which feeds the packaged
// version); this one feeds BuildConfig.VERSION, shown in the About screen and compared by the
// update checker. The release workflow sets APP_VERSION from the git tag, so the two agree.
val appVersion: String =
    (project.findProperty("appVersion") as String?)
        ?: System.getenv("APP_VERSION")
        ?: "0.0.0"

val generatedBuildConfigDir = layout.buildDirectory.dir("generated/buildConfig/kotlin")

abstract class GenerateBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val dropboxAppKey: Property<String>

    @get:Input
    abstract val oneDriveClientId: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val updateRepo: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkgDir = outputDir.get().asFile.resolve("works/merc/keryx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            |package works.merc.keryx.app
            |
            |// Auto-generated. Do not edit by hand.
            |object BuildConfig {
            |    const val DROPBOX_APP_KEY: String = "${dropboxAppKey.get()}"
            |    const val ONEDRIVE_CLIENT_ID: String = "${oneDriveClientId.get()}"
            |    const val VERSION: String = "${versionName.get()}"
            |    const val UPDATE_REPO: String = "${updateRepo.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateBuildConfig = tasks.register<GenerateBuildConfigTask>("generateBuildConfig") {
    dropboxAppKey.set(resolvedDropboxAppKey)
    oneDriveClientId.set(resolvedOneDriveClientId)
    versionName.set(appVersion)
    updateRepo.set(resolvedUpdateRepo)
    outputDir.set(generatedBuildConfigDir)
}

// Google Drive is desktop-only (see CloudStorageAvailability.android.kt / PlatformModule.android.kt
// — Android has no Google Drive provider, per sync-architecture.md's "Google Drive on Android").
// Its client secret must therefore never reach a source set Android compiles against: generated
// into its own object, in its own directory, attached only to desktopMain below — not the
// jvmCommonMain the main BuildConfig lives in — so it cannot end up in the APK/AAB even for a
// developer whose local.properties happens to hold real Google Drive credentials.
val generatedDesktopBuildConfigDir = layout.buildDirectory.dir("generated/desktopBuildConfig/kotlin")

// Desktop-only counterpart holding the Google Drive OAuth client id/secret — see
// generatedDesktopBuildConfigDir's own comment above for why this is split out of BuildConfig.
abstract class GenerateDesktopBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val googleDriveClientId: Property<String>

    @get:Input
    abstract val googleDriveClientSecret: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkgDir = outputDir.get().asFile.resolve("works/merc/keryx/app")
        pkgDir.mkdirs()
        pkgDir.resolve("DesktopBuildConfig.kt").writeText(
            """
            |package works.merc.keryx.app
            |
            |// Auto-generated. Do not edit by hand.
            |object DesktopBuildConfig {
            |    const val GOOGLE_DRIVE_CLIENT_ID: String = "${googleDriveClientId.get()}"
            |    const val GOOGLE_DRIVE_CLIENT_SECRET: String = "${googleDriveClientSecret.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateDesktopBuildConfig = tasks.register<GenerateDesktopBuildConfigTask>("generateDesktopBuildConfig") {
    googleDriveClientId.set(resolvedGoogleDriveClientId)
    googleDriveClientSecret.set(resolvedGoogleDriveClientSecret)
    outputDir.set(generatedDesktopBuildConfigDir)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvmToolchain(25)

    compilerOptions {
        // expect/actual classes are still flagged "Beta"; we use them intentionally.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    // Android's D8/R8 cannot read Java 25 bytecode, so this target compiles to 17 while the
    // desktop target above stays on 25 — see composeApp/build.gradle.kts's android block.
    android {
        namespace = "works.merc.keryx.app.shared"
        compileSdk = 37
        minSdk = 26

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }

        // Instrumented ("device") tests for DatabaseMerger/DatabaseSnapshot's Android actuals —
        // requery's bundled SQLite is a native library that only loads on a real device/emulator.
        // See composeApp/build.gradle.kts's own withDeviceTestBuilder for why the tree is named
        // "deviceTest" rather than "test".
        withDeviceTestBuilder {
            sourceSetTreeName = "deviceTest"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }
    }

    // The native Apple app (macOS first, iOS later) consumes this module as the KeryxShared
    // XCFramework — see docs/app-architecture.md's "Apple Native Apps (SwiftUI)". Static, so the
    // app links one binary rather than embedding a dynamic framework. Apple Silicon only, like the
    // existing macOS release (and lifecycle-viewmodel publishes no macosX64 variant anyway).
    val xcframework = XCFramework("KeryxShared")
    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "KeryxShared"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        // BuildConfig (the OAuth client keys, version and update repo) is plain constants, readable
        // from every target — including Apple, which has no jvmCommonMain.
        commonMain {
            kotlin.srcDir(generatedBuildConfigDir)
        }
        commonMain.dependencies {
            api(libs.koin.core)
            api(libs.sqldelight.coroutines.extensions)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.io.core)

            implementation(libs.ksoup)

            // The shared state holders (presentation/) are ViewModels, which the Apple app can use
            // too — this is the UI-framework-free artifact, not the Compose one.
            api(libs.lifecycle.viewmodel)
        }

        // Shared by desktop and Android: the JVM-library-backed actuals (java.io.File,
        // java.util.zip, java.security.MessageDigest) that work verbatim on both. Anything needing
        // an Android Context (AppDirs) or an Android-idiomatic API (Log) stays in each target's
        // own source set instead.
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
        }
        getByName("desktopMain").dependsOn(jvmCommonMain)
        getByName("androidMain").dependsOn(jvmCommonMain)

        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.core.ktx)
                // GoogleApiAvailability, for CloudStorageAvailability's "is Google Drive offerable
                // on this device" check — see composeApp/build.gradle.kts for the auth flow itself.
                implementation(libs.play.services.auth)

                // The bundled SQLite that backs articles_fts's trigram tokenizer. See
                // .claude/rules/android-sqlite-bundling.md for why this is required and the
                // conditions under which it can be dropped.
                api(libs.sqldelight.driver.android)
                api(libs.requery.sqlite.android)
            }
        }

        // Apple source sets, wired by hand: the custom jvmCommonMain above turns off Kotlin's
        // default hierarchy template, which would otherwise create these. appleMain holds what both
        // platforms share; macosMain/iosMain only what differs (AppKit vs UIKit).
        val appleMain = create("appleMain") { dependsOn(commonMain.get()) }
        val macosMain = create("macosMain") { dependsOn(appleMain) }
        val iosMain = create("iosMain") { dependsOn(appleMain) }
        getByName("macosArm64Main").dependsOn(macosMain)
        getByName("iosArm64Main").dependsOn(iosMain)
        getByName("iosSimulatorArm64Main").dependsOn(iosMain)
        val appleTest = create("appleTest") { dependsOn(commonTest.get()) }
        // macOS-only tests: the Keychain round trip. Kotlin/Native runs iOS tests as a bare
        // executable in the simulator, outside any app bundle, where no keychain is available
        // (errSecNotAvailable); on macOS the test binary reaches the login keychain directly.
        val macosTest = create("macosTest") { dependsOn(appleTest) }
        getByName("macosArm64Test").dependsOn(macosTest)
        listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { getByName(it).dependsOn(appleTest) }

        // Apple actuals: CommonCrypto/zlib/Security/Foundation and the system sqlite3 come from the
        // Kotlin/Native platform libraries; only the HTTP engine and the SQLDelight driver are extra.
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            api(libs.sqldelight.driver.native)
        }

        getByName("desktopMain") {
            kotlin.srcDir(generatedDesktopBuildConfigDir)
            dependencies {
                api(libs.sqldelight.driver.jdbc.sqlite)
                api(libs.sqlite.jdbc)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(project(":testing"))
        }

        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                // LogTest checks that a third-party slf4j caller reaches Log's root JUL handler —
                // through the same slf4j-jdk14 binding :composeApp's desktop app ships with.
                implementation(libs.slf4j.jdk14)
            }
        }

        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.androidx.test.runner.device.test)
                implementation(libs.androidx.test.junit.device.test)
                implementation(libs.requery.sqlite.android)
            }
        }
    }
}

// The generated BuildConfig must exist before any Kotlin compilation runs. DesktopBuildConfig is
// only ever in desktopMain's srcDir (see the sourceSets block above), so making every compilation
// task depend on generateDesktopBuildConfig too is harmless — it just writes an unused file for
// non-desktop targets — and keeping the dependency unconditional avoids matching compile task
// names against the target name here.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildConfig)
    dependsOn(generateDesktopBuildConfig)
}

sqldelight {
    databases {
        create("KeryxDatabase") {
            packageName.set("works.merc.keryx.app.data.local.db")
            srcDirs.setFrom("src/commonMain/sqldelight")
            dialect(libs.sqldelight.dialect.sqlite338)
            // Disables build-time migration verification. This should remain off until
            // `.sqm` migration files are introduced; without them, verification will
            // fail because it cannot reconstruct a migration chain. Re-enable when you
            // add your first migration file.
            verifyMigrations.set(false)
        }
    }
}
