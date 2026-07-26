import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
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

// jpackage rejects a macOS app-version whose first component is 0 (it enforces the CFBundleVersion
// rule that versions start at 1), which would otherwise make 0.x impossible to release at all —
// it fails createDistributable, not just the DMG step. So a 0.x build is packaged under this
// placeholder and the real, user-visible version is written back into Info.plist afterwards
// (see restoreMacOsShortVersion). Versions >= 1 are passed through untouched.
val isZeroMajorVersion = appVersion.substringBefore('.').toIntOrNull() == 0
val macOsPackageVersion = if (isZeroMajorVersion) "1.0.0" else appVersion

val generatedBuildConfigDir = layout.buildDirectory.dir("generated/buildConfig/kotlin")

abstract class GenerateBuildConfigTask : DefaultTask() {
    @get:Input
    abstract val dropboxAppKey: Property<String>

    @get:Input
    abstract val googleDriveClientId: Property<String>

    @get:Input
    abstract val googleDriveClientSecret: Property<String>

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

            implementation(libs.ksoup)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)

            // Spike: OS-native WebView for article content view (see plan doc).
            implementation(libs.composewebview)
        }

        getByName("desktopMain") {
            kotlin.srcDir(generatedBuildConfigDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)

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
        jvmArgs("-Dapple.awt.application.name=Keryx")
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Keryx"
            packageVersion = appVersion
            description = "Local-first, cross-platform RSS reader"
            vendor = "Keryx"
            // java.sql: sqlite-jdbc, java.naming: keyring, java.desktop: AWT tray,
            // jdk.httpserver: OAuth loopback callback server.
            modules("java.sql", "java.naming", "java.desktop", "jdk.httpserver")

            macOS {
                bundleID = "works.merc.keryx"
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
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/keryx.ico"))
                menuGroup = "Keryx"
                dirChooser = true
            }
            linux {
                iconFile.set(project.file("icons/keryx.png"))
            }
        }
    }
}

compose.resources {
    packageOfResClass = "works.merc.keryx.app.resources"
}

/**
 * Restores the user-visible version that [macOsPackageVersion] had to replace to get past
 * jpackage's "app-version cannot start with 0" check. CFBundleVersion deliberately keeps the
 * placeholder: it is an internal build identifier that never surfaces in Finder or the UI.
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
}

// The `run` task is registered lazily by the Compose/KMP desktop plugins, so other
// run-specific configuration is deferred until after project evaluation.
afterEvaluate {
    // 0.x only: jpackage would not accept the real version, so it is written back here. This
    // breaks the ad-hoc bundle seal, which is acceptable while the app is effectively unsigned
    // (see docs/build.md). A major version of 1 or higher needs no post-processing at all and
    // keeps a valid signature. packageDmg depends on createDistributable, so patching here also
    // reaches the app bundle inside the DMG.
    tasks.findByName("createDistributable")?.doLast {
        if (isZeroMajorVersion) {
            restoreMacOsShortVersion(
                file("build/compose/binaries/main/app/Keryx.app/Contents/Info.plist"),
                appVersion,
            )
        }
    }

    // Inject MimeType for custom URI scheme support on Linux
    tasks.findByName("packageDeb")?.doLast {
        val desktopFile = fileTree("build/compose/binaries/main/deb").matching {
            include("**/*.desktop")
        }.firstOrNull()
        desktopFile?.let { file ->
            var content = file.readText()
            if (!content.contains("MimeType")) {
                content = content.trimEnd() + "\nMimeType=x-scheme-handler/keryx;\n"
                file.writeText(content)
            }
        }
    }

    // Set DMG volume icon to the app icon instead of the default Java logo.
    // Compose Desktop's macOS iconFile only targets the .app bundle, not the DMG mount icon.
    tasks.findByName("packageDmg")?.doLast {
        val dmgDir = file("build/compose/binaries/main/dmg")
        val dmgFile = dmgDir.listFiles { _, name -> name.endsWith(".dmg") }?.singleOrNull()
            ?: return@doLast
        val volumeName = "Keryx"
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
