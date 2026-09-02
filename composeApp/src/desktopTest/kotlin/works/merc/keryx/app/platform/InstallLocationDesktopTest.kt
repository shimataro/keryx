package works.merc.keryx.app.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the path-parsing logic in `InstallLocation.desktop.kt` directly, independent of the
 * real `jpackage.app-path`/OS this test itself runs under (see `detectInstallLocation`'s own
 * dispatch, which these internal `detect*InstallLocation` functions sit behind). Writability
 * outcomes use real temporary directories rather than mocks, since [FileSystemExtras.isDirectoryWritable]
 * does real filesystem probing that a mock would misrepresent.
 */
class InstallLocationDesktopTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File =
        createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        // Some tests revoke write permission on a directory to exercise the "unwritable" branch;
        // restore it before recursive delete, or cleanup itself would fail.
        tempDirs.forEach { it.setWritable(true) }
        tempDirs.forEach { it.deleteRecursively() }
    }

    // --- macOS ---

    @Test
    fun macAppBundleDetectedFromContentsMacOsLayout() {
        val root = newTempDir("install-location-mac")
        val macOsDir = File(root, "Keryx.app/Contents/MacOS").apply { mkdirs() }
        val launcherFile = File(macOsDir, "Keryx")

        val location = detectMacInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.MAC_APP_BUNDLE, location.kind)
        assertEquals(File(root, "Keryx.app").path, location.appRoot)
        assertTrue(location.parentWritable)
        assertFalse(location.translocated)
    }

    @Test
    fun macAppBundleUnderAppTranslocationIsFlagged() {
        val root = newTempDir("install-location-mac-translocation")
        val macOsDir = File(root, "AppTranslocation/ABCD1234-5678/d/Keryx.app/Contents/MacOS").apply { mkdirs() }
        val launcherFile = File(macOsDir, "Keryx")

        val location = detectMacInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.MAC_APP_BUNDLE, location.kind)
        assertTrue(location.translocated)
    }

    @Test
    fun macLayoutNotMatchingAppContentsMacOsIsUnknown() {
        val launcherFile = File("/some/random/place/Keryx")

        val location = detectMacInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.UNKNOWN, location.kind)
    }

    // --- Windows ---

    @Test
    fun windowsPortableWhenTheInstallParentIsWritable() {
        val root = newTempDir("install-location-windows")
        val appDir = File(root, "Keryx").apply { mkdirs() }
        val launcherFile = File(appDir, "Keryx.exe")

        val location = detectWindowsInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.WINDOWS_PORTABLE, location.kind)
        assertEquals(appDir.path, location.appRoot)
        assertTrue(location.parentWritable)
    }

    // File.setWritable(false) on a directory is itself ignored by Windows' own filesystem
    // (FILE_ATTRIBUTE_READONLY has no effect there), so this negative probe only works on the
    // POSIX platforms this JVM might run the test suite under — a real Windows ACL would be
    // needed to exercise the unwritable branch on Windows itself, out of scope here.
    @Test
    fun windowsInstalledWhenTheInstallParentIsUnwritable() {
        if (isWindows) return

        val root = newTempDir("install-location-windows-unwritable")
        val parent = File(root, "parent").apply { mkdirs() }
        val appDir = File(parent, "Keryx").apply { mkdirs() }
        val launcherFile = File(appDir, "Keryx.exe")
        check(parent.setWritable(false)) { "test setup: could not revoke write permission" }

        val location = detectWindowsInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.WINDOWS_INSTALLED, location.kind)
        assertFalse(location.parentWritable)
    }

    // --- Linux ---

    @Test
    fun linuxPortableWhenTheInstallParentIsWritable() {
        val root = newTempDir("install-location-linux")
        val binDir = File(root, "Keryx/bin").apply { mkdirs() }
        val launcherFile = File(binDir, "Keryx")

        val location = detectLinuxInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.LINUX_PORTABLE, location.kind)
        assertEquals(File(root, "Keryx").path, location.appRoot)
        assertTrue(location.parentWritable)
    }

    // Same Windows caveat as windowsInstalledWhenTheInstallParentIsUnwritable() above — this
    // exercises the Linux detector, but the CI leg it would run under is still whichever OS the
    // JVM itself is on, and setWritable(false) on a directory is a no-op on Windows.
    @Test
    fun linuxUnknownWhenTheInstallParentIsUnwritable() {
        if (isWindows) return

        val root = newTempDir("install-location-linux-unwritable")
        val parent = File(root, "parent").apply { mkdirs() }
        val binDir = File(parent, "Keryx/bin").apply { mkdirs() }
        val launcherFile = File(binDir, "Keryx")
        check(parent.setWritable(false)) { "test setup: could not revoke write permission" }

        val location = detectLinuxInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.UNKNOWN, location.kind)
        assertFalse(location.parentWritable)
    }

    @Test
    fun linuxUnderOptIsAlwaysAPackageInstallRegardlessOfWritability() {
        // Purely path-based — detectLinuxInstallLocation checks this before ever touching the
        // filesystem, so a non-existent path is fine here.
        val launcherFile = File("/opt/keryx/bin/Keryx")

        val location = detectLinuxInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.LINUX_PACKAGE, location.kind)
        assertFalse(location.parentWritable)
    }

    @Test
    fun linuxUnderUsrIsAlwaysAPackageInstall() {
        val launcherFile = File("/usr/bin/Keryx")

        val location = detectLinuxInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.LINUX_PACKAGE, location.kind)
    }

    @Test
    fun linuxLayoutNotMatchingBinIsUnknown() {
        val launcherFile = File("/some/random/place/Keryx")

        val location = detectLinuxInstallLocation(launcherFile.path, launcherFile)

        assertEquals(InstallKind.UNKNOWN, location.kind)
    }

}
