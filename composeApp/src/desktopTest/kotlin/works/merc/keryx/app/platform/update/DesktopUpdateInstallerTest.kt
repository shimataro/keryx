package works.merc.keryx.app.platform.update

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import works.merc.keryx.app.platform.isWindows

/**
 * Exercises [DesktopUpdateInstaller] against a [FakeProcessLauncher] so a self-replace or msiexec
 * hand-off is only ever asserted by its command line, never actually run — this is the whole point
 * of the [ProcessLauncher] seam (see its own KDoc). Every extraction/staging step still runs for
 * real against temp directories, since that's exactly the logic worth catching regressions in.
 */
class DesktopUpdateInstallerTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File = createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private class FakeProcessLauncher(private val result: Boolean = true) : ProcessLauncher {
        var callCount = 0
            private set
        var lastCommand: List<String>? = null
            private set

        override fun launch(command: List<String>): Boolean {
            callCount++
            lastCommand = command
            return result
        }
    }

    private fun macAppZip(
        destDir: File,
        version: String = "1.2.3",
        includeExecutable: Boolean = true,
        // Separate from `version`, which also names the file: a crafted plist value can contain
        // characters a filename cannot.
        plistVersion: String = version,
    ): File {
        val zipFile = File(destDir, "Keryx-$version-macos-arm64.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            if (includeExecutable) {
                zip.putNextEntry(ZipEntry("Keryx.app/Contents/MacOS/Keryx"))
                zip.write("binary".encodeToByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("Keryx.app/Contents/Info.plist"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist><dict>
                <key>CFBundleShortVersionString</key>
                <string>$plistVersion</string>
                </dict></plist>
                """.trimIndent().encodeToByteArray(),
            )
            zip.closeEntry()
        }
        return zipFile
    }

    private fun linuxAppZip(destDir: File, version: String = "1.2.3", includeExecutable: Boolean = true): File {
        val zipFile = File(destDir, "Keryx-$version-linux-x86_64.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            if (includeExecutable) {
                zip.putNextEntry(ZipEntry("Keryx/bin/Keryx"))
                zip.write("binary".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun windowsAppZip(destDir: File, version: String = "1.2.3", includeExe: Boolean = true): File {
        val zipFile = File(destDir, "Keryx-$version-windows-x86_64.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            if (includeExe) {
                zip.putNextEntry(ZipEntry("Keryx/Keryx.exe"))
                zip.write("binary".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private fun macAsset(version: String = "1.2.3") =
        UpdateAsset("Keryx-$version-macos-arm64.zip", "https://example.invalid/x.zip", 1_000, "0".repeat(64), UpdateAssetKind.MAC_APP_ZIP)

    private fun update(version: String, asset: UpdateAsset?, plan: UpdatePlan) =
        AvailableUpdate(version, "https://example.invalid/release", null, asset, plan)

    // --- canInstall ---

    @Test
    fun canInstallSelfReplaceOnAWritableUntranslocatedMacBundle() {
        val root = newTempDir("desktop-installer-can-mac")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        assertTrue(installer.canInstall(UpdatePlan.SelfReplace(macAsset())))
    }

    @Test
    fun canInstallRefusesATranslocatedMacBundle() {
        val root = newTempDir("desktop-installer-can-mac-translocated")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = true)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        assertFalse(installer.canInstall(UpdatePlan.SelfReplace(macAsset())))
    }

    @Test
    fun canInstallRefusesAnUnwritableInstallParent() {
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, "/Applications/Keryx.app", "/Applications/Keryx.app/Contents/MacOS/Keryx", parentWritable = false, translocated = false)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        assertFalse(installer.canInstall(UpdatePlan.SelfReplace(macAsset())))
    }

    @Test
    fun canInstallRunInstallerOnlyForAnInstalledWindowsMsiForm() {
        val installedLocation = InstallLocation(InstallKind.WINDOWS_INSTALLED, "C:\\Program Files\\Keryx", "C:\\Program Files\\Keryx\\Keryx.exe", parentWritable = false, translocated = false)
        val msiAsset = UpdateAsset("Keryx-1.2.3-windows-x86_64.msi", "https://example.invalid/x.msi", 1_000, "0".repeat(64), UpdateAssetKind.WINDOWS_MSI)
        val installer = DesktopUpdateInstaller(installedLocation, FakeProcessLauncher())

        assertTrue(installer.canInstall(UpdatePlan.RunInstaller(msiAsset)))
    }

    @Test
    fun canInstallNeverAcceptsAnAndroidApkOnDesktop() {
        val installedLocation = InstallLocation(InstallKind.WINDOWS_INSTALLED, "C:\\Program Files\\Keryx", "C:\\Program Files\\Keryx\\Keryx.exe", parentWritable = false, translocated = false)
        val apkAsset = UpdateAsset("Keryx-1.2.3-android-universal.apk", "https://example.invalid/x.apk", 1_000, "0".repeat(64), UpdateAssetKind.ANDROID_APK)
        val installer = DesktopUpdateInstaller(installedLocation, FakeProcessLauncher())

        assertFalse(installer.canInstall(UpdatePlan.RunInstaller(apkAsset)))
    }

    @Test
    fun canInstallRefusesOpenReleasePageAndNotOffered() {
        val root = newTempDir("desktop-installer-can-refuse-plans")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        assertFalse(installer.canInstall(UpdatePlan.OpenReleasePage))
        assertFalse(installer.canInstall(UpdatePlan.NotOffered))
    }

    // --- install(): macOS self-replace ---

    @Test
    fun installMacSelfReplaceExtractsStagesAndLaunchesTheApplyScript() {
        val root = newTempDir("desktop-installer-mac")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertEquals(InstallLaunchResult.Launched, result)
        assertEquals(1, launcher.callCount)

        val stagedApp = File(root, ".Keryx.app.new")
        assertTrue(stagedApp.isDirectory, "the extracted app must be staged next to the current install")
        assertTrue(File(stagedApp, "Contents/MacOS/Keryx").canExecute())

        val command = launcher.lastCommand!!
        assertEquals("/bin/sh", command[0])
        assertTrue(command[1].endsWith("apply.sh"))
        assertEquals(ProcessHandle.current().pid().toString(), command[2])
        assertEquals(appRoot.path, command[3])
        assertEquals(stagedApp.path, command[4])
        assertEquals(File(root, ".Keryx.app.old").path, command[5])
        assertTrue(command[6].endsWith("apply.log"))

        val scriptFile = File(command[1])
        assertTrue(scriptFile.canExecute(), "the launched script must be marked executable")
        assertContainsMacSelfReplaceShape(scriptFile.readText())
    }

    private fun assertContainsMacSelfReplaceShape(script: String) {
        assertTrue(script.contains("mv \"\$APP\" \"\$OLD\""))
        assertTrue(script.contains("open -n -a \"\$APP\""))
    }

    /**
     * Regression guard: once the extracted app is staged next to the current install, neither the
     * downloaded ZIP nor its extraction directory is still needed — leaving them for the next
     * check()'s sweep could mean never, on a Failed retry loop (see
     * UpdateRepository.retryFailed), and both sit on the same cache volume runDownload's own
     * free-space guard already budgets tightly for.
     */
    @Test
    fun installMacSelfReplaceCleansUpTheZipAndExtractionDirImmediatelyAfterStaging() {
        val root = newTempDir("desktop-installer-mac-cleanup")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-cleanup-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertEquals(InstallLaunchResult.Launched, result)
        assertFalse(zip.exists(), "the downloaded ZIP must be removed once staged")
        assertFalse(File(downloadDir, "extracted").exists(), "the extraction directory must be removed once staged")
    }

    /**
     * Regression guard: every self-replace script's own retreat step is a plain
     * `mv "$APP" "$OLD"` (never delete-then-move, by design), so a `.old` sibling left behind by a
     * previous abandoned attempt must be cleared *before* launching a new one — otherwise that `mv`
     * nests into it (`.old/Keryx.app`) instead of overwriting it, and a later rollback would restore
     * the wrapper directory rather than the actual app.
     */
    @Test
    fun installMacSelfReplaceClearsAStaleOldDirBeforeLaunching() {
        val root = newTempDir("desktop-installer-mac-stale-old")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val staleOld = File(root, ".Keryx.app.old").apply { mkdirs() }
        File(staleOld, "leftover-from-a-past-attempt.txt").writeText("stale")
        val downloadDir = newTempDir("desktop-installer-mac-stale-old-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val installer = DesktopUpdateInstaller(location, FakeProcessLauncher())

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertEquals(InstallLaunchResult.Launched, result)
        assertFalse(
            File(staleOld, "leftover-from-a-past-attempt.txt").exists(),
            "a stale .old sibling must be cleared, not left for the script's mv to nest into",
        )
    }

    @Test
    fun installMacSelfReplaceFailsAndNeverLaunchesWhenTheBundleVersionDoesNotMatch() {
        val root = newTempDir("desktop-installer-mac-version-mismatch")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-version-mismatch-download")
        val zip = macAppZip(downloadDir, version = "9.9.9")
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertIs<InstallLaunchResult.Failed>(result)
        assertEquals(0, launcher.callCount, "a failed health check must never launch the swap script")
        assertFalse(File(root, ".Keryx.app.new").exists())
    }

    @Test
    fun installMacSelfReplaceBoundsWhatACraftedPlistVersionCanPutIntoTheFailureReason() {
        val root = newTempDir("desktop-installer-mac-plist-injection")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-plist-injection-download")
        // The reason string is written verbatim to the app log by UpdateRepository, so a value that
        // can carry newlines could forge log lines, and an unbounded one could push earlier entries
        // out through rotation.
        val zip = macAppZip(downloadDir, plistVersion = "9.9.9\nFORGED LOG LINE " + "A".repeat(500))
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        val failed = assertIs<InstallLaunchResult.Failed>(result)
        assertFalse(failed.reason.contains("\n"), "a plist value must not be able to inject a newline: ${failed.reason}")
        assertTrue(failed.reason.length < 200, "a plist value must not be able to grow the reason unbounded: ${failed.reason.length}")
        assertEquals(0, launcher.callCount)
    }

    @Test
    fun installMacSelfReplaceFailsAndNeverLaunchesWhenTheCodeSignatureSelfCheckFails() {
        val root = newTempDir("desktop-installer-mac-codesign-fail")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-codesign-fail-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher, CodeSigningVerifier { "simulated signature failure" })

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertIs<InstallLaunchResult.Failed>(result)
        assertEquals(0, launcher.callCount, "a failed self-consistency check must never launch the swap script")
        assertFalse(File(root, ".Keryx.app.new").exists())
    }

    @Test
    fun installMacSelfReplaceChecksTheExtractedBundlesOwnSignatureBeforeLaunching() {
        val root = newTempDir("desktop-installer-mac-codesign-pass")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-codesign-pass-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val verifiedPaths = mutableListOf<String>()
        val installer = DesktopUpdateInstaller(location, launcher, CodeSigningVerifier { path -> verifiedPaths.add(path); null })

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertEquals(InstallLaunchResult.Launched, result)
        assertEquals(1, verifiedPaths.size, "the self-check must run exactly once, against the extracted bundle")
        assertEquals(1, launcher.callCount)
    }

    @Test
    fun installMacSelfReplaceFailsAndNeverLaunchesWhenTheArchiveExtractorRejectsTheArchive() {
        val root = newTempDir("desktop-installer-mac-extract-reject")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-extract-reject-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(
            location,
            launcher,
            CodeSigningVerifier { null },
            ArchiveExtractor { _, _, _, _ -> error("ditto could not extract the update archive (exit 1)") },
        )

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        val failed = assertIs<InstallLaunchResult.Failed>(result)
        // The reason is the only trace an install failure leaves anywhere (UpdateRepository logs it;
        // the UI collapses every stage to one string), so it has to carry the extractor's own words.
        assertTrue(failed.reason.contains("exit 1"), "the extractor's reason must reach the caller: ${failed.reason}")
        assertEquals(0, launcher.callCount, "a rejected archive must never launch the swap script")
        assertFalse(File(root, ".Keryx.app.new").exists())
    }

    @Test
    fun installMacSelfReplaceFailsAndNeverLaunchesWhenTheArchiveExtractorThrowsAnIoException() {
        val root = newTempDir("desktop-installer-mac-extract-io")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-extract-io-download")
        val zip = macAppZip(downloadDir)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        // A missing or unrunnable ditto arrives as an IOException from ProcessBuilder.start(), not
        // as the IllegalStateException a rejected archive throws — a separate catch clause, so a
        // separate test.
        val installer = DesktopUpdateInstaller(
            location,
            launcher,
            CodeSigningVerifier { null },
            ArchiveExtractor { _, _, _, _ -> throw IOException("boom") },
        )

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        val failed = assertIs<InstallLaunchResult.Failed>(result)
        assertTrue(failed.reason.contains("boom"), "the I/O failure's own message must reach the caller: ${failed.reason}")
        assertEquals(0, launcher.callCount, "a failed extraction must never launch the swap script")
        assertFalse(File(downloadDir, "extracted").exists(), "a failed extraction must not leave its partial tree behind")
    }

    // Removing a directory entry needs write permission on its parent, which is how this makes
    // deleteRecursively fail; Windows' permission model doesn't reproduce it.
    @Test
    fun installMacSelfReplaceFailsWhenTheStaleStagingDirectoryCannotBeCleared() {
        if (isWindows) return

        val root = newTempDir("desktop-installer-mac-stale-unclearable")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-stale-unclearable-download")
        val zip = macAppZip(downloadDir)
        File(downloadDir, "extracted").mkdirs()
        File(downloadDir, "extracted/leftover.txt").writeText("undeletable")
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher, CodeSigningVerifier { null })

        downloadDir.setWritable(false)
        val result = try {
            runBlocking {
                installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
            }
        } finally {
            downloadDir.setWritable(true) // so tearDown can clean up
        }

        assertIs<InstallLaunchResult.Failed>(result)
        assertEquals(0, launcher.callCount, "extracting into a stale tree must never reach the swap script")
        assertFalse(File(root, ".Keryx.app.new").exists())
    }

    @Test
    fun installMacSelfReplaceClearsAStalePartialExtractionBeforeExtracting() {
        val root = newTempDir("desktop-installer-mac-stale-extract")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-stale-extract-download")
        val zip = macAppZip(downloadDir)
        // What an attempt killed mid-extraction leaves behind — an extractor that merges into an
        // existing directory would otherwise mix it into the next attempt's bundle.
        File(downloadDir, "extracted/Keryx.app/Contents").mkdirs()
        File(downloadDir, "extracted/Keryx.app/Contents/leftover.txt").writeText("from a killed attempt")
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        var staleWasStillThere: Boolean? = null
        val installer = DesktopUpdateInstaller(
            location,
            launcher,
            CodeSigningVerifier { null },
            ArchiveExtractor { zipPath, destDir, maxBytes, executableEntries ->
                staleWasStillThere = File(destDir, "Keryx.app/Contents/leftover.txt").exists()
                InProcessArchiveExtractor.extract(zipPath, destDir, maxBytes, executableEntries)
            },
        )

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset("1.2.3"), UpdatePlan.SelfReplace(macAsset("1.2.3"))))
        }

        assertEquals(InstallLaunchResult.Launched, result)
        assertEquals(false, staleWasStillThere, "the staging directory must be cleared before extraction starts")
        assertFalse(File(root, ".Keryx.app.new/Contents/leftover.txt").exists())
    }

    @Test
    fun installMacSelfReplaceFailsWhenTheExtractedLauncherIsMissing() {
        val root = newTempDir("desktop-installer-mac-missing-exe")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-mac-missing-exe-download")
        val zip = macAppZip(downloadDir, includeExecutable = false)
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, File(appRoot, "Contents/MacOS/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking {
            installer.install(zip.path, update("1.2.3", macAsset(), UpdatePlan.SelfReplace(macAsset())))
        }

        assertIs<InstallLaunchResult.Failed>(result)
        assertEquals(0, launcher.callCount)
    }

    // --- install(): Linux / Windows portable self-replace ---

    @Test
    fun installLinuxSelfReplaceLaunchesTheApplyScriptWithNoLogArgumentOmitted() {
        val root = newTempDir("desktop-installer-linux")
        val appRoot = File(root, "Keryx").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-linux-download")
        val zip = linuxAppZip(downloadDir)
        val asset = UpdateAsset("Keryx-1.2.3-linux-x86_64.zip", "https://example.invalid/x.zip", 1_000, "0".repeat(64), UpdateAssetKind.LINUX_ZIP)
        val location = InstallLocation(InstallKind.LINUX_PORTABLE, appRoot.path, File(appRoot, "bin/Keryx").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking { installer.install(zip.path, update("1.2.3", asset, UpdatePlan.SelfReplace(asset))) }

        assertEquals(InstallLaunchResult.Launched, result)
        val stagedApp = File(root, ".Keryx.new")
        assertTrue(File(stagedApp, "bin/Keryx").canExecute())
        val command = launcher.lastCommand!!
        assertEquals("/bin/sh", command[0])
        assertEquals(7, command.size, "linux self-replace still takes a log-path argument")
        assertTrue(command.last().endsWith("apply.log"))
    }

    @Test
    fun installWindowsSelfReplaceLaunchesViaCmdWithNoLogArgument() {
        val root = newTempDir("desktop-installer-windows")
        val appRoot = File(root, "Keryx").apply { mkdirs() }
        val downloadDir = newTempDir("desktop-installer-windows-download")
        val zip = windowsAppZip(downloadDir)
        val asset = UpdateAsset("Keryx-1.2.3-windows-x86_64.zip", "https://example.invalid/x.zip", 1_000, "0".repeat(64), UpdateAssetKind.WINDOWS_ZIP)
        val location = InstallLocation(InstallKind.WINDOWS_PORTABLE, appRoot.path, File(appRoot, "Keryx.exe").path, parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking { installer.install(zip.path, update("1.2.3", asset, UpdatePlan.SelfReplace(asset))) }

        assertEquals(InstallLaunchResult.Launched, result)
        val command = launcher.lastCommand!!
        assertEquals(listOf("cmd", "/c"), command.take(2))
        assertTrue(command[2].endsWith("apply.cmd"))
        assertEquals(7, command.size)
        assertFalse(
            command.any { it.endsWith(".log") },
            "windows self-replace takes no log-path argument (see UpdateScriptWriter)",
        )
    }

    // --- install(): Windows MSI ---

    @Test
    fun installWindowsMsiLaunchesMsiexecScriptAgainstTheDownloadedFile() {
        val downloadDir = newTempDir("desktop-installer-msi-download")
        val msi = File(downloadDir, "Keryx-1.2.3-windows-x86_64.msi").apply { writeText("msi bytes") }
        val asset = UpdateAsset("Keryx-1.2.3-windows-x86_64.msi", "https://example.invalid/x.msi", 1_000, "0".repeat(64), UpdateAssetKind.WINDOWS_MSI)
        val location = InstallLocation(InstallKind.WINDOWS_INSTALLED, "C:\\Program Files\\Keryx", "C:\\Program Files\\Keryx\\Keryx.exe", parentWritable = false, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking { installer.install(msi.path, update("1.2.3", asset, UpdatePlan.RunInstaller(asset))) }

        assertEquals(InstallLaunchResult.Launched, result)
        val command = launcher.lastCommand!!
        assertEquals(listOf("cmd", "/c"), command.take(2))
        assertTrue(command[2].endsWith("apply.cmd"))
        assertEquals(ProcessHandle.current().pid().toString(), command[3])
        assertEquals(msi.path, command[4])
        assertEquals("C:\\Program Files\\Keryx\\Keryx.exe", command[5])
        assertTrue(command[6].endsWith("apply.log"))
    }

    // --- install(): guard rails that must never reach the launcher ---

    @Test
    fun installFailsWithoutLaunchingWhenNoAssetWasSelected() {
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, "/Applications/Keryx.app", "/Applications/Keryx.app/Contents/MacOS/Keryx", parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)

        val result = runBlocking { installer.install("/tmp/whatever.zip", update("1.2.3", null, UpdatePlan.OpenReleasePage)) }

        assertIs<InstallLaunchResult.Failed>(result)
        assertEquals(0, launcher.callCount)
    }

    @Test
    fun installFailsWithoutLaunchingForOpenReleasePageOrNotOffered() {
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, "/Applications/Keryx.app", "/Applications/Keryx.app/Contents/MacOS/Keryx", parentWritable = true, translocated = false)
        val launcher = FakeProcessLauncher()
        val installer = DesktopUpdateInstaller(location, launcher)
        val asset = macAsset()

        val releasePageResult = runBlocking { installer.install("/tmp/whatever.zip", update("1.2.3", asset, UpdatePlan.OpenReleasePage)) }
        val notOfferedResult = runBlocking { installer.install("/tmp/whatever.zip", update("1.2.3", asset, UpdatePlan.NotOffered)) }

        assertIs<InstallLaunchResult.Failed>(releasePageResult)
        assertIs<InstallLaunchResult.Failed>(notOfferedResult)
        assertEquals(0, launcher.callCount)
    }

    // --- hasEnoughFreeSpace ---

    @Test
    fun hasEnoughFreeSpaceIsTrueExactlyAtTheRequiredMultiple() {
        assertTrue(hasEnoughFreeSpace(usableBytes = 1_000, baseBytes = 100, multiple = 10))
    }

    @Test
    fun hasEnoughFreeSpaceIsFalseOneByteShortOfTheRequiredMultiple() {
        assertFalse(hasEnoughFreeSpace(usableBytes = 999, baseBytes = 100, multiple = 10))
    }

    @Test
    fun hasEnoughFreeSpaceRejectsABaseThatWouldOverflowMultiplication() {
        // Mirrors domain.UpdateFreeSpaceGuardTest's own overflow case, for the same reason: a plain
        // usableBytes < baseBytes * multiple would silently overflow into a negative Long here too.
        val hugeBase = Long.MAX_VALUE / 2
        assertFalse(hasEnoughFreeSpace(usableBytes = Long.MAX_VALUE, baseBytes = hugeBase, multiple = 10L))
    }

    @Test
    fun hasEnoughFreeSpaceRejectsANegativeBase() {
        assertFalse(hasEnoughFreeSpace(usableBytes = Long.MAX_VALUE, baseBytes = -1, multiple = 10))
    }

    // --- cleanUpStaleSelfReplaceArtifacts ---

    @Test
    fun cleanUpStaleSelfReplaceArtifactsRemovesBothStaleSiblings() {
        val root = newTempDir("desktop-installer-stale-cleanup")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val staleNew = File(root, ".Keryx.app.new").apply { mkdirs() }
        val staleOld = File(root, ".Keryx.app.old").apply { mkdirs() }
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, null, parentWritable = true, translocated = false)

        cleanUpStaleSelfReplaceArtifacts(location)

        assertFalse(staleNew.exists())
        assertFalse(staleOld.exists())
        assertTrue(appRoot.exists(), "the live install itself must never be touched")
    }

    @Test
    fun cleanUpStaleSelfReplaceArtifactsIsANoOpWhenThereIsNothingStale() {
        val root = newTempDir("desktop-installer-stale-cleanup-noop")
        val appRoot = File(root, "Keryx.app").apply { mkdirs() }
        val location = InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot.path, null, parentWritable = true, translocated = false)

        cleanUpStaleSelfReplaceArtifacts(location) // must not throw when there's nothing to remove

        assertTrue(appRoot.exists())
    }

    @Test
    fun cleanUpStaleSelfReplaceArtifactsIgnoresInstallKindsThatNeverSelfReplace() {
        val root = newTempDir("desktop-installer-stale-cleanup-not-self-replace")
        val appDir = File(root, "Keryx").apply { mkdirs() }
        val staleNew = File(root, ".Keryx.new").apply { mkdirs() }
        // WINDOWS_INSTALLED never self-replaces (see updatePlan) — a stale sibling next to it (were
        // one ever to exist) is out of scope for this cleanup and must be left alone.
        val location = InstallLocation(InstallKind.WINDOWS_INSTALLED, appDir.path, null, parentWritable = true, translocated = false)

        cleanUpStaleSelfReplaceArtifacts(location)

        assertTrue(staleNew.exists())
    }

    @Test
    fun cleanUpStaleSelfReplaceArtifactsIsANoOpWithNoAppRoot() {
        val location = InstallLocation(InstallKind.UNKNOWN, appRoot = null, launcherPath = null, parentWritable = false, translocated = false)

        cleanUpStaleSelfReplaceArtifacts(location) // must not throw
    }
}
