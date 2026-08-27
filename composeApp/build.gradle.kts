import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
// packaging-metadata spot below (packageName, vendor, the macOS Dock-name jvmArg, the Windows
// Start Menu group, the Info.plist patch path, the DMG volume name) reads from this one val.
val appName = "Keryx"

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

abstract class GenerateBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val dropboxAppKey: Property<String>

    @get:Input
    abstract val googleDriveClientId: Property<String>

    @get:Input
    abstract val googleDriveClientSecret: Property<String>

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
            |    const val GOOGLE_DRIVE_CLIENT_ID: String = "${googleDriveClientId.get()}"
            |    const val GOOGLE_DRIVE_CLIENT_SECRET: String = "${googleDriveClientSecret.get()}"
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
    googleDriveClientId.set(resolvedGoogleDriveClientId)
    googleDriveClientSecret.set(resolvedGoogleDriveClientSecret)
    oneDriveClientId.set(resolvedOneDriveClientId)
    versionName.set(appVersion)
    updateRepo.set(resolvedUpdateRepo)
    outputDir.set(generatedBuildConfigDir)
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

// The generated BuildConfig must exist before any Kotlin compilation runs.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildConfig)
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
            vendor = appName
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
                // The keryx:// scheme is not registered here. jpackage only emits a .desktop file
                // when given a shortcut or a file association, and its template's Exec line has no
                // %u — so the URI would never reach the process. LinuxUriSchemeRegistrar writes a
                // user-level .desktop entry at startup instead, which also covers app-image and
                // tarball installs.
            }
        }
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
 */
fun restoreMacOsShortVersion(infoPlist: java.io.File, version: String) {
    if (!infoPlist.exists()) return
    val content = infoPlist.readText()
    val patched = Regex("""(<key>CFBundleShortVersionString</key>\s*<string>)[^<]*(</string>)""")
        .replace(content) { "${it.groupValues[1]}$version${it.groupValues[2]}" }
    if (patched != content) infoPlist.writeText(patched)
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
    // Needed whenever what actually got packaged (macOsPackageVersion) differs from the real,
    // full appVersion: the 0.x-major placeholder, a stripped pre-release suffix, or both at once.
    // This breaks the ad-hoc bundle seal, which is acceptable while the app is effectively
    // unsigned (see docs/build.md). A plain (non-prerelease), major-1-or-higher version needs no
    // post-processing at all and keeps a valid signature. packageDmg depends on
    // createDistributable, so patching here also reaches the app bundle inside the DMG.
    tasks.findByName("createDistributable")?.doLast {
        if (macOsPackageVersion != appVersion) {
            restoreMacOsShortVersion(
                file("build/compose/binaries/main/app/$appName.app/Contents/Info.plist"),
                appVersion,
            )
        }
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

        fun runCommand(vararg args: String, captureOutput: Boolean = false): String {
            val process = ProcessBuilder(*args).apply {
                if (!captureOutput) inheritIO()
            }.start()
            val output = if (captureOutput) process.inputStream.bufferedReader().readText() else ""
            val exit = process.waitFor()
            if (exit != 0) error("Command failed (exit $exit): ${args.joinToString(" ")}")
            return output
        }

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
}
