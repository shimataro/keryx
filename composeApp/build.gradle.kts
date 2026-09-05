import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // No version here: the root project already puts AGP on the build classpath.
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
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

// --- Resolve ONEDRIVE_CLIENT_ID: -PoneDriveClientId > env var > local.properties > empty ---
// An empty id hides the OneDrive option from the UI entirely (see CloudStorageAvailability).
// OneDrive is a PKCE public client (native/desktop), so no client secret is required.
val resolvedOneDriveClientId: String =
    (project.findProperty("oneDriveClientId") as String?)
        ?: System.getenv("ONEDRIVE_CLIENT_ID")
        ?: localProperties.getProperty("onedrive.client.id")
        ?: ""

// --- Resolve UPDATE_REPO: -PupdateRepo > env var > local.properties > default ---
// GitHub "owner/repo" slug the update checker polls via the public releases/latest API.
// Unlike the three secrets above, an empty value here is not a meaningful "disabled" state —
// it would produce `repos//releases/latest` and make every update check fail — so blank values
// fall through to the next candidate instead of being taken literally.
val resolvedUpdateRepo: String =
    (project.findProperty("updateRepo") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("UPDATE_REPO")?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty("update.repo")?.takeIf { it.isNotBlank() }
        ?: "shimataro/keryx"

// --- Resolve the app version: -PappVersion > env var > the literal below ---
// Single source of truth for the app version, shared by BuildConfig.VERSION (shown in the
// About screen) and the native-distribution packageVersion below. The release workflow
// derives it from the git tag (`v0.1.0` -> `0.1.0`) so the tag and the packaged version can
// never disagree; local builds fall through to the literal.
val appVersion: String =
    (project.findProperty("appVersion") as String?)
        ?: System.getenv("APP_VERSION")
        ?: "0.0.0"

// Single source of truth for the app's display name within this build script. Mirrors (but is a
// separate literal from) core/Constants.kt's APP_NAME: this script evaluates before composeApp's
// own commonMain is compiled, so it cannot reference that Kotlin constant directly. Every
// packaging-metadata spot below (packageName, the macOS Dock-name jvmArg, the Windows Start Menu
// group, the Info.plist patch path, the DMG volume name) reads from this one val.
val appName = "Keryx"

// The publishing entity, independent of appName (the product's own display name). Feeds
// nativeDistributions.vendor, which surfaces as the rpm `Vendor:` tag and the Windows installer's
// Publisher property — kept separate from appName so a product name and its publisher's identity
// don't collide, matching the "Mercury Works <keryx@merc.works>" identity used for the deb
// Maintainer / AppStream <developer> (see the linux { } block below).
val appVendor = "Mercury Works"

// Project homepage, shared by the Linux packages' `--about-url` (deb `Homepage:` / rpm `URL:`)
// and the AppStream metainfo's <url type="homepage">. Mirrors README.md's "Website" line.
val appHomepageUrl = "https://keryx.merc.works"

// jpackage's packaging metadata (CFBundleVersion, RPM %version, MSI ProductVersion) must stay
// purely numeric MAJOR.MINOR.PATCH — unlike BuildConfig.VERSION, it cannot carry a SemVer
// pre-release suffix (`-beta.1`, `-rc.2`, ...). Stripped from appVersion by dropping everything
// from the first `-` onward; a plain (non-prerelease) appVersion — including the local-dev
// "0.0.0" default — has nothing to strip, so packaging is unaffected for ordinary builds.
val appPackageVersion: String = appVersion.substringBefore('-')

// jpackage rejects a macOS app-version whose first component is 0 (it enforces the CFBundleVersion
// rule that versions start at 1), which would otherwise make 0.x impossible to release at all —
// it fails createDistributable, not just the DMG step. So a 0.x build is packaged under this
// placeholder and the real, user-visible version is written back into Info.plist afterwards
// (see restoreMacOsShortVersion). Versions >= 1 are passed through untouched. Uses
// appPackageVersion (not appVersion) since that is already the numeric-only value jpackage needs.
val isZeroMajorVersion = appPackageVersion.substringBefore('.').toIntOrNull() == 0
val macOsPackageVersion = if (isZeroMajorVersion) "1.0.0" else appPackageVersion

// Android's versionCode is a single monotonically-increasing integer, so MAJOR.MINOR.PATCH is
// folded into one number two decimal digits per component (1.2.3 -> 10203, 0.1.2 -> 102). This
// caps MINOR and PATCH at 99 each, which is well beyond anything this project's tagging produces.
// Derived from appPackageVersion (already stripped of any pre-release suffix) so a `-beta.1` build
// and its final release share a versionCode — Play would reject a re-upload at the same code, but
// pre-release builds are not published there (see release.yml, which skips installers for them).
val androidVersionCode: Int = appPackageVersion.split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { parts ->
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        // Each component must fit the two decimal digits reserved for it, or two distinct
        // versions could fold to the same versionCode (e.g. 1.100.0 and 2.0.0 both -> 20000).
        require(minor in 0..99 && patch in 0..99) {
            "androidVersionCode encoding requires MINOR and PATCH in 0..99, got $appPackageVersion"
        }
        major * 10000 + minor * 100 + patch
    }
    // versionCode must be >= 1; the local-dev "0.0.0" default would otherwise fold to 0.
    .coerceAtLeast(1)

val generatedBuildConfigDir = layout.buildDirectory.dir("generated/buildConfig/kotlin")

// Google Drive is desktop-only (see CloudStorageAvailability.android.kt / PlatformModule.android.kt
// — Android has no Google Drive provider, per sync-architecture.md's "Google Drive on Android").
// Its client secret must therefore never reach a source set Android compiles against: generated
// into its own object, in its own directory, attached only to desktopMain below — not the shared
// jvmCommonMain the main BuildConfig lives in — so it cannot end up in the APK/AAB even for a
// developer whose local.properties happens to hold real Google Drive credentials.
val generatedDesktopBuildConfigDir = layout.buildDirectory.dir("generated/desktopBuildConfig/kotlin")

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
    // desktop target above stays on 25. jvmToolchain(25) still applies — that selects the JDK
    // that *runs* the compiler, which is independent of the bytecode level it emits.
    android {
        namespace = "works.merc.keryx.app"
        compileSdk = 37
        // 26 (Android 8.0) is what lets Pkce's java.util.Base64 usage be shared from
        // jvmCommonMain rather than needing an android.util.Base64 actual of its own. The
        // bundled SQLite (see .claude/rules/android-sqlite-bundling.md) supports 21, so it is
        // not what sets this floor.
        minSdk = 26

        // Without this, Compose Resources generates nothing for androidMain at all (every
        // *ForAndroidMain resource task reports NO-SOURCE) and the app crashes at runtime with
        // MissingResourceException the first time any commonMain code reads a string resource —
        // confirmed on-device. See https://youtrack.jetbrains.com/issue/CMP-9547 (Compose
        // Multiplatform resources aren't packaged into the Android APK under AGP 9's
        // com.android.kotlin.multiplatform.library plugin without this opt-in).
        androidResources.enable = true

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }

        // Instrumented ("device") tests for DatabaseMerger/DatabaseSnapshot's Android actuals —
        // requery's bundled SQLite is a native library that only loads on a real device/emulator,
        // so these can't run as a plain JVM unit test the way desktopTest does. Opt-in only
        // (disabled by default in AGP 9's KMP library plugin): see docs/testing.md for how to run
        // them and .claude/CLAUDE.md's testing conventions for what belongs here vs. desktopTest.
        //
        // withDeviceTestBuilder (not the separate top-level withDeviceTest{}) is what actually
        // enables the androidDeviceTest source set/compilation; calling both throws ("Android
        // device tests have already been enabled ... You can create only one component of type
        // android (device, DEVICE_TEST)"). withDeviceTestBuilder's own return value is what lets
        // instrumentationRunner/execution be configured, via HasConfigurableValue.configure.
        //
        // sourceSetTreeName is deliberately its own unique tree ("deviceTest"), not the
        // conventional "test" tree commonTest/desktopTest share — confirmed on-device that
        // "test" pulls the *entire* commonTest source set into the instrumented compilation, and
        // several commonTest tests (e.g. StringsXmlParityTest, which reads
        // composeResources/values/strings.xml by a JVM-relative java.io.File path) fail outright
        // in an Android instrumentation process, which has no such working directory. A distinct
        // tree name keeps androidDeviceTest scoped to its own sources plus commonMain/androidMain.
        withDeviceTestBuilder {
            sourceSetTreeName = "deviceTest"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.sqldelight.coroutines.extensions)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)

            implementation(libs.ksoup)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)

            // Spike: OS-native WebView for article content view (see plan doc).
            implementation(libs.composewebview)
        }

        // Shared by desktop and Android: the JVM-library-backed actuals (java.io.File,
        // java.util.zip, java.security.MessageDigest, java.util.Base64) that work verbatim on
        // both. Anything needing an Android Context (AppDirs) or an Android-idiomatic API (Log)
        // stays in each target's own source set instead.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(generatedBuildConfigDir)
        }
        getByName("desktopMain").dependsOn(jvmCommonMain)
        getByName("androidMain").dependsOn(jvmCommonMain)

        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.work)
                implementation(libs.ktor.client.okhttp)

                // The bundled SQLite that backs articles_fts's trigram tokenizer. See
                // .claude/rules/android-sqlite-bundling.md for why this is required and the
                // conditions under which it can be dropped.
                implementation(libs.sqldelight.driver.android)
                implementation(libs.requery.sqlite.android)
            }
        }

        getByName("desktopMain") {
            kotlin.srcDir(generatedDesktopBuildConfigDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.flatlaf)

                implementation(libs.sqldelight.driver.jdbc.sqlite)
                implementation(libs.sqlite.jdbc)
                implementation(libs.ktor.client.cio)

                // java-keyring pulls in the non-JPMS JNA artifacts, but Gradle's
                // variant resolution mistakenly looks for a non-existent
                // "jna-<version>-jpms.jar". Exclude the transitive jna/jna-platform
                // and substitute the explicit JPMS artifacts instead. The string
                // notation is required because the source-set DSL has no
                // `implementation(Provider, configure)` overload for a catalog entry.
                implementation("com.github.javakeyring:java-keyring:${libs.versions.javakeyring.get()}") {
                    exclude(group = "net.java.dev.jna", module = "jna")
                    exclude(group = "net.java.dev.jna", module = "jna-platform")
                }
                implementation(libs.jna.jpms)
                implementation(libs.jna.platform.jpms)

                implementation(libs.slf4j.simple)

                // Linux tray (StatusNotifierItem) + desktop notifications. Added
                // unconditionally so the cross-platform CI matrix resolves the same
                // graph everywhere; nothing touches D-Bus until SniConnection is built,
                // which only happens on Linux.
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport.unixsocket)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.driver.jdbc.sqlite)
                implementation(libs.sqlite.jdbc)
                implementation(libs.compose.ui.test)
            }
        }

        // See android { withDeviceTestBuilder { ... } } above for why this exists — a real
        // device/emulator is required to load requery's bundled SQLite native library.
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

compose.desktop {
    application {
        mainClass = "works.merc.keryx.app.MainKt"
        // macOS: without this, native AWT dialogs (FileDialog) ignore the OS dark mode setting.
        jvmArgs("-Dapple.awt.application.appearance=system")
        jvmArgs("-Dapple.awt.application.name=$appName")
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = appName
            packageVersion = appPackageVersion
            description = "Local-first, cross-platform RSS reader"
            vendor = appVendor
            // NSHumanReadableCopyright (macOS Info.plist, shown in Finder's "Get Info") — without
            // this, Compose's own Info.plist builder falls back to the generic
            // "Copyright (C) <year>" with no attribution at all. Confirmed against Compose's
            // AbstractJPackageTask source that no other target format's template (deb/rpm/msi)
            // reads this value, so it's a no-op there, not just unused.
            copyright = "Copyright (c) ${LocalDate.now().year} Mercury Works"
            // Linux-only: an unconditional licenseFile would also add a license-acceptance page
            // to the Windows MSI, changing its install flow for a problem that is Linux-specific
            // (the deb's /usr/share/doc/<pkg>/copyright and the rpm's %license section).
            if (System.getProperty("os.name").startsWith("Linux")) {
                licenseFile.set(rootProject.file("LICENSE"))
            }
            // java.sql: sqlite-jdbc, java.naming: keyring, java.desktop: AWT tray,
            // jdk.httpserver: OAuth loopback callback server,
            // jdk.security.auth: dbus-java's SASL EXTERNAL auth resolves the uid through
            //   com.sun.security.auth.module.UnixSystem on every non-Windows host. Without
            //   it the jlink image builds fine but the packaged .deb/.rpm dies with
            //   NoClassDefFoundError, while `./gradlew run` (full JDK) works.
            // jdk.localedata: the Linux OPML file chooser (platform/FilePicker.desktop.kt) is a
            //   javax.swing.JFileChooser, whose built-in chrome ("Open", "Cancel", "File name", …)
            //   comes from the JDK's own com.sun.swing.internal.plaf.basic.resources.basic
            //   bundles — FlatLaf ships no ja translation of its own for these keys. Without this
            //   module the chooser renders in English on a packaged (jlink'd) Linux build only;
            //   `./gradlew run` (full JDK) doesn't show the gap.
            modules("java.sql", "java.naming", "java.desktop", "jdk.httpserver", "jdk.security.auth", "jdk.localedata")

            macOS {
                bundleID = "works.merc.keryx"
                appCategory = "public.app-category.news"
                iconFile.set(project.file("icons/keryx.icns"))
                // Overridden only for macOS — deb/rpm/msi accept a 0.x version, jpackage's macOS
                // path does not. See macOsPackageVersion above.
                packageVersion = macOsPackageVersion
                // Custom URI scheme for the Dropbox OAuth callback. This goes through the
                // plugin's Info.plist template rather than a post-build patch so that it is
                // present *before* jpackage signs the bundle — patching afterwards breaks the
                // bundle seal (codesign then reports "invalid Info.plist").
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>works.merc.keryx.oauth</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>keryx</string>
                                </array>
                            </dict>
                        </array>
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>OPML Document</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSHandlerRank</key>
                                <string>Default</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>org.opml.opml</string>
                                    <string>com.reederapp.opml</string>
                                    <string>unofficial.opml</string>
                                </array>
                            </dict>
                        </array>
                        <key>UTImportedTypeDeclarations</key>
                        <array>
                            <dict>
                                <key>UTTypeIdentifier</key>
                                <string>org.opml.opml</string>
                                <key>UTTypeDescription</key>
                                <string>OPML Document</string>
                                <key>UTTypeConformsTo</key>
                                <array>
                                    <string>public.xml</string>
                                </array>
                                <key>UTTypeTagSpecification</key>
                                <dict>
                                    <key>public.filename-extension</key>
                                    <array>
                                        <string>opml</string>
                                    </array>
                                </dict>
                            </dict>
                            <dict>
                                <key>UTTypeIdentifier</key>
                                <string>com.reederapp.opml</string>
                                <key>UTTypeDescription</key>
                                <string>OPML Document</string>
                                <key>UTTypeConformsTo</key>
                                <array>
                                    <string>public.xml</string>
                                </array>
                                <key>UTTypeTagSpecification</key>
                                <dict>
                                    <key>public.filename-extension</key>
                                    <array>
                                        <string>opml</string>
                                    </array>
                                </dict>
                            </dict>
                            <dict>
                                <key>UTTypeIdentifier</key>
                                <string>unofficial.opml</string>
                                <key>UTTypeDescription</key>
                                <string>OPML Document</string>
                                <key>UTTypeConformsTo</key>
                                <array>
                                    <string>public.xml</string>
                                </array>
                                <key>UTTypeTagSpecification</key>
                                <dict>
                                    <key>public.filename-extension</key>
                                    <array>
                                        <string>opml</string>
                                    </array>
                                </dict>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/keryx.ico"))
                menuGroup = appName
                dirChooser = true
                // Fixed GUID so Windows Installer recognizes successive MSI releases as upgrades
                // of the same product instead of unrelated installs (jpackage/WiX MajorUpgrade).
                // Do not change this value once released.
                upgradeUuid = "a9b7a997-b367-49da-b40e-7e6e4ea66c60"
            }
            linux {
                iconFile.set(project.file("icons/keryx.png"))
                menuGroup = "Network;News;Feed;"
                // rpm's `License:` tag — jpackage's own default is the literal "Unknown" without this.
                rpmLicenseType = "MIT"
                // deb's `Maintainer:` — an email address only; jpackage itself renders the tag as
                // "<vendor> <this address>" (verified against `jpackage --help`'s own description
                // of the option), so passing a "Name <email>" pair here doubles up into
                // "Mercury Works <Mercury Works <keryx@merc.works>>". Without this property at
                // all, jpackage's own default is <build-user>@<build-host>. A role address, not a
                // personal one: it ends up permanently in every published .deb, readable via
                // `apt show`. Matches the AppStream <developer> / <update_contact> identity below.
                debMaintainer = "keryx@merc.works"
                // deb's `Section:` / rpm's `Group:` — distinct from menuGroup above (which maps to
                // the .desktop file's Categories).
                appCategory = "net"
                // Makes jpackage emit and register a system .desktop file (DesktopIntegration only
                // does this given a shortcut or a file association — otherwise deb/rpm ship no
                // .desktop at all). Needed for the app to show up in the applications menu, and for
                // AppStream metainfo's <launchable type="desktop-id"> to have a target to point at.
                // The keryx:// scheme is still NOT registered through this: jpackage's own .desktop
                // template has no %u on its Exec line, so the URI would never reach the process
                // this way. LinuxUriSchemeRegistrar keeps writing its own user-level .desktop entry
                // at startup for that — under a different filename (see its own doc comment) so the
                // two never collide — which also covers app-image and tarball installs that have no
                // packaged .desktop at all.
                shortcut = true
            }
        }
    }
}

// --about-url is the only way to fill the deb's `Homepage:`, the rpm's `URL:`, and the MSI's
// "Support link" (ARPURLINFOABOUT, shown in Windows Settings > Apps) — the Compose
// nativeDistributions DSL has no matching property for any of them, so it goes to jpackage
// directly. Not passed to Dmg/AppImage: confirmed against Compose's own macOS Info.plist builder
// (setInfoPlistValues) that no key there reads it, so it would be a silent no-op — jpackage
// itself never errors on an option a given target format's template doesn't use.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb || targetFormat == TargetFormat.Rpm || targetFormat == TargetFormat.Msi) {
        freeArgs.addAll("--about-url", appHomepageUrl)
    }
}

compose.resources {
    packageOfResClass = "works.merc.keryx.app.resources"
}

/**
 * Restores the user-visible version that [macOsPackageVersion] had to replace — either jpackage's
 * "app-version cannot start with 0" check, a stripped SemVer pre-release suffix (see
 * [appPackageVersion]), or both. CFBundleVersion deliberately keeps the packaged value: it is an
 * internal build identifier that never surfaces in Finder or the UI.
 *
 * @return whether the file was actually rewritten. jpackage signs the bundle *before* this runs, so
 *   `true` means the seal is now stale and the caller must re-sign (see [resealMacOsBundle]).
 */
fun restoreMacOsShortVersion(infoPlist: java.io.File, version: String): Boolean {
    if (!infoPlist.exists()) return false
    val content = infoPlist.readText()
    val patched = Regex("""(<key>CFBundleShortVersionString</key>\s*<string>)[^<]*(</string>)""")
        .replace(content) { "${it.groupValues[1]}$version${it.groupValues[2]}" }
    if (patched == content) return false
    infoPlist.writeText(patched)
    return true
}

/**
 * Runs [args] to completion, failing the build on a non-zero exit. Shared by both macOS
 * post-processing steps (`createDistributable`'s re-sign/verify and `packageDmg`'s volume-icon
 * hook) so a change to how this build reports a failed external tool only has to be made once.
 *
 * @param captureOutput return the command's stdout instead of inheriting it.
 * @param errorMessage what to say instead of naming the command line, for a caller whose failure
 *   deserves an explanation rather than an invocation.
 */
fun runCommand(vararg args: String, captureOutput: Boolean = false, errorMessage: String? = null): String {
    val process = ProcessBuilder(*args).apply { if (!captureOutput) inheritIO() }.start()
    val output = if (captureOutput) process.inputStream.bufferedReader().readText() else ""
    val exit = process.waitFor()
    if (exit != 0) error(errorMessage?.let { "$it (exited $exit)" } ?: "Command failed (exit $exit): ${args.joinToString(" ")}")
    return output
}

/** Runs `codesign` with [args], failing the build via [errorMessage] on a non-zero exit. */
fun runCodesign(errorMessage: String, vararg args: String) {
    runCommand("/usr/bin/codesign", *args, errorMessage = errorMessage)
}

/**
 * The signature properties a re-sign must not silently change, read off `codesign -dv`'s
 * `CodeDirectory` line: `flags=` (is the hardened runtime on?) and `hashes=13+N`, whose `N` counts
 * the special slots and is therefore what reveals whether an **entitlement blob exists at all**.
 * That pairing is the whole point — a bundle hardened with `runtime` but stripped of its
 * entitlements is killed by AMFI on launch, and `codesign --verify` cannot see it, since the seal
 * itself is perfectly valid. `size=` is deliberately excluded: it varies legitimately.
 */
fun macSignatureProperties(appDir: java.io.File): List<String> {
    val process = ProcessBuilder("/usr/bin/codesign", "-dv", appDir.absolutePath)
        .redirectErrorStream(true) // codesign -dv reports on stderr
        .start()
    val output = process.inputStream.bufferedReader().readText()
    process.waitFor()
    val line = output.lineSequence().firstOrNull { it.startsWith("CodeDirectory ") } ?: return emptyList()
    return line.split(' ').filter { it.startsWith("flags=") || it.startsWith("hashes=") }
}

/**
 * Re-applies the signature that [restoreMacOsShortVersion]'s write-back invalidated, carrying over
 * everything the previous signature declared.
 *
 * `--preserve-metadata=entitlements,flags,runtime` is load-bearing, not defensive. Compose Desktop
 * signs the app image with `default-entitlements.plist` — `allow-jit`,
 * `allow-unsigned-executable-memory`, `disable-library-validation` — **and** the hardened-runtime
 * flag, all three of which a JVM needs to run at all on Apple Silicon. Naming `--options runtime`
 * by hand reproduced the flag while dropping the entitlements (`hashes=13+7` became `13+3`), which
 * leaves a bundle that passes `codesign --verify` and is then killed by AMFI the moment it launches.
 * Preserving the previous signature's metadata reproduces whatever was there instead of restating a
 * guess, so it cannot drift if Compose or jpackage change what they sign with.
 *
 * `-` (ad-hoc) is hardcoded because this build configures no signing identity at all; adopting a
 * Developer ID means passing that identity here instead — re-signing ad-hoc over it would silently
 * discard it and defeat notarization (see docs/build.md).
 */
fun resealMacOsBundle(appDir: java.io.File) {
    runCodesign(
        "Failed to re-sign $appDir after restoring CFBundleShortVersionString",
        "--force", "--deep", "--preserve-metadata=entitlements,flags,runtime",
        "--sign", "-", appDir.absolutePath,
    )
}

/**
 * Fails the build if [appDir]'s signature no longer matches its own contents. The in-app updater
 * runs this exact check against every downloaded bundle before swapping it in (see
 * docs/background-update.md), so an app image that can't pass it must never leave this machine —
 * the release ZIP is made from this very directory.
 */
fun verifyMacOsBundleSeal(appDir: java.io.File) {
    runCodesign(
        "$appDir has a broken code-signature seal and must not be shipped",
        "--verify", "--strict", "--deep", appDir.absolutePath,
    )
}

/**
 * The single `.desktop` filename jpackage placed inside the `.deb` payload — emitted because
 * `linux.shortcut = true` is set above. jpackage names it `<packageName>-<launcher>.desktop`
 * (LinuxAppImageBuilder), a convention that already satisfies xdg-desktop-menu's own
 * vendor-prefix requirement, so the same name is what ends up registered under
 * /usr/share/applications after install — exactly what AppStream's <launchable> needs to name.
 * Discovered by walking the extracted payload rather than reconstructed from packageName/appName,
 * so a future change to either can't silently produce a metainfo file pointing at nothing.
 *
 * Must check [java.io.File.isFile]: the bundled JDK runtime's `legal/` directory has one
 * subdirectory per module, and `legal/java.desktop/` (license notices for the `java.desktop`
 * platform module — AWT/Swing) is a directory whose name also ends in ".desktop".
 */
fun findPackagedDesktopFileName(payloadDir: java.io.File): String {
    val desktopFiles = payloadDir.walkTopDown().filter { it.isFile && it.extension == "desktop" }.toList()
    return desktopFiles.singleOrNull()?.name
        ?: error("Expected exactly one .desktop file under $payloadDir, found: ${desktopFiles.map { it.path }}")
}

/**
 * Injects AppStream metainfo into a built `.deb` so software centers (GNOME Software, Ubuntu App
 * Center, KDE Discover) can show the license and homepage links the `.deb` control file has no
 * fields for at all (see composeApp/packaging/linux/works.merc.keryx.metainfo.xml.in). No-op when
 * `dpkg-deb` isn't on PATH — a `.deb` is only ever actually produced on a Linux CI runner or a
 * Linux dev machine, never by a macOS/Windows local build.
 */
fun injectDebMetainfo(debFile: java.io.File, metainfoTemplate: java.io.File, packageVersion: String) {
    val dpkgDebAvailable = runCatching {
        ProcessBuilder("dpkg-deb", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
    if (!dpkgDebAvailable) return

    // A path dpkg-deb can create itself: -R/--raw-extract refuses to write into a directory that
    // already exists.
    val workDir = Files.createTempFile("keryx-deb-metainfo", "").toFile().apply { delete() }
    try {
        runCommand("dpkg-deb", "-R", debFile.absolutePath, workDir.absolutePath)

        val desktopId = findPackagedDesktopFileName(workDir)
        val metainfoContent = metainfoTemplate.readText()
            .replace("@DESKTOP_ID@", desktopId)
            .replace("@VERSION@", packageVersion)
            .replace("@DATE@", LocalDate.now().toString())

        val metainfoRelativePath = "usr/share/metainfo/works.merc.keryx.metainfo.xml"
        val metainfoFile = workDir.resolve(metainfoRelativePath)
        metainfoFile.parentFile.mkdirs()
        metainfoFile.writeText(metainfoContent)

        // Best-effort: dpkg-deb --build does not itself validate md5sums against the payload, but
        // keeping the control file accurate matches how a normal Debian package is built.
        val md5 = MessageDigest.getInstance("MD5").digest(metainfoFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        val md5sumsFile = workDir.resolve("DEBIAN/md5sums")
        val existingMd5sums = md5sumsFile.takeIf { it.exists() }?.readText().orEmpty()
        val separator = if (existingMd5sums.isNotEmpty() && !existingMd5sums.endsWith("\n")) "\n" else ""
        md5sumsFile.writeText("$existingMd5sums$separator$md5  $metainfoRelativePath\n")

        debFile.delete()
        runCommand("dpkg-deb", "--build", "--root-owner-group", workDir.absolutePath, debFile.absolutePath)
    } finally {
        workDir.deleteRecursively()
    }
}

// Silences the sqlite-jdbc restricted-method (System::load) warning on JDK 22+.
// Covers JavaExec tasks (run) and Test tasks (desktopTest/commonTest).
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Now that values-en/strings.xml exists alongside the Japanese default, Compose Resources
    // resolution genuinely depends on the JVM's locale (previously inert, since only one locale
    // existed). Several tests assert literal resource text; pin to Japanese so results don't
    // depend on the host's own locale (e.g. CI runners typically default to English).
    jvmArgs("-Duser.language=ja", "-Duser.country=JP")
}

// The `run` task is registered lazily by the Compose/KMP desktop plugins, so other
// run-specific configuration is deferred until after project evaluation.
afterEvaluate {
    // macOS-only post-processing: no other platform produces an .app, so every step below is a
    // no-op there. The version write-back is needed whenever what actually got packaged
    // (macOsPackageVersion) differs from the real, full appVersion — the 0.x-major placeholder, a
    // stripped pre-release suffix, or both at once. jpackage signs the bundle *before* this runs,
    // so the write-back invalidates the ad-hoc seal and the bundle must be re-signed: the in-app
    // updater's own `codesign --verify --strict --deep` gate would otherwise reject every release
    // ZIP made from this directory (the DMG never showed the problem, because jpackage re-signs its
    // own copy of the app image while building it). The verify runs whether or not anything was
    // patched, so a broken seal can never leave this machine unnoticed. packageDmg depends on
    // createDistributable, so all of this also reaches the app bundle inside the DMG.
    tasks.findByName("createDistributable")?.doLast {
        val appDir = file("build/compose/binaries/main/app/$appName.app")
        if (!appDir.isDirectory) return@doLast
        if (macOsPackageVersion != appVersion) {
            val before = macSignatureProperties(appDir)
            // appDir.resolve(...), not java.io.File(...): inside a task action `java` resolves to
            // the JavaPluginExtension, not the package, so the qualified name doesn't compile here.
            if (restoreMacOsShortVersion(appDir.resolve("Contents/Info.plist"), appVersion)) {
                resealMacOsBundle(appDir)
                val after = macSignatureProperties(appDir)
                // The seal verify below cannot catch a re-sign that produced a *valid* signature
                // with different properties than jpackage's — most consequentially a hardened
                // runtime that lost its entitlements. Comparing before and after is the only thing
                // that can, so a drift here fails the build rather than shipping a bundle that
                // verifies cleanly and dies at launch.
                check(before == after) {
                    "Re-signing $appDir changed its signature properties: $before -> $after"
                }
            }
        }
        verifyMacOsBundleSeal(appDir)
    }

    // Set DMG volume icon to the app icon instead of the default Java logo.
    // Compose Desktop's macOS iconFile only targets the .app bundle, not the DMG mount icon.
    tasks.findByName("packageDmg")?.doLast {
        val dmgDir = file("build/compose/binaries/main/dmg")
        val dmgFile = dmgDir.listFiles { _, name -> name.endsWith(".dmg") }?.singleOrNull()
            ?: return@doLast
        val volumeName = appName
        val rwDmg = File("${dmgFile.absolutePath}.rw.dmg")
        val iconFile = file("icons/keryx.icns")

        try {
            // Convert to read-write so we can modify the volume contents
            runCommand("hdiutil", "convert", dmgFile.absolutePath, "-format", "UDRW", "-o", rwDmg.absolutePath)

            // Attach and capture the device node for later detach
            val attachOut = runCommand(
                "hdiutil", "attach", "-nobrowse", "-noverify", "-noautoopen", rwDmg.absolutePath,
                captureOutput = true
            )
            val device = attachOut.trim().lines().lastOrNull()
                ?.split(Regex("\\s+"))?.firstOrNull()
                ?: error("Failed to parse hdiutil attach output")

            try {
                // Brief pause to ensure the volume is visible on disk
                Thread.sleep(500)

                val volumePath = "/Volumes/$volumeName"
                iconFile.copyTo(File(volumePath, ".VolumeIcon.icns"), overwrite = true)

                // Enable custom icon flag on the volume root and hide the icon file
                runCommand("SetFile", "-a", "C", volumePath)
                runCommand("SetFile", "-a", "V", "$volumePath/.VolumeIcon.icns")
                runCommand("hdiutil", "detach", device)
            } catch (e: Exception) {
                // Best-effort detach on failure so we don't leave a mount behind
                try {
                    runCommand("hdiutil", "detach", device)
                } catch (_: Exception) { /* ignored */ }
                throw e
            }

            // Replace original compressed DMG
            dmgFile.delete()
            runCommand("hdiutil", "convert", rwDmg.absolutePath, "-format", "UDZO", "-o", dmgFile.absolutePath)
        } finally {
            rwDmg.delete()
        }
    }

    // Linux-only in effect: this task object exists on every host (Compose registers it
    // unconditionally) but only actually runs on Linux — Compose disables it elsewhere via
    // `packageTask.enabled = packageTask.targetFormat.isCompatibleWithCurrentOS` — so this doLast
    // never fires on a macOS/Windows build.
    tasks.findByName("packageDeb")?.let { packageDebTask ->
        val metainfoTemplate = file("packaging/linux/works.merc.keryx.metainfo.xml.in")
        // Declared explicitly: the template is read inside doLast via a plain file() call, which
        // Gradle's up-to-date check does not see on its own — without this, editing the template
        // alone would leave packageDeb UP-TO-DATE and silently keep shipping the old metainfo.
        packageDebTask.inputs.file(metainfoTemplate)
        packageDebTask.doLast {
            val debFile = file("build/compose/binaries/main/deb")
                .listFiles { _, name -> name.endsWith(".deb") }
                ?.singleOrNull()
                ?: return@doLast
            injectDebMetainfo(debFile, metainfoTemplate, appPackageVersion)
        }
    }
}
