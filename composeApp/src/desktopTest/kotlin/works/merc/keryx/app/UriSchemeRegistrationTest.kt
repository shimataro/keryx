package works.merc.keryx.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UriSchemeRegistrationTest {
    /** Reads a `reg.exe import <path>` command's temp `.reg` file back as text, stripping the UTF-16LE BOM. */
    private fun regFileTextFor(importCommand: List<String>): String {
        val path = importCommand.last()
        val bytes = File(path).readBytes()
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }

    @Test
    fun normalizesAFileUriArgToAPlainPath() {
        // Uses a real, OS-native absolute path (not a hardcoded Unix literal) because
        // java.io.File(URI)'s conversion is platform-dependent — a driveless "/home/user/..."
        // path (never a value Windows would actually hand this app) round-trips differently
        // through Windows' WinNTFileSystem than through Unix's, which broke this test on
        // windows-latest CI. File.toURI()'s rawPath always starts with "/" (a drive letter on
        // Windows, e.g. "/C:/Users/..."), so prefixing it with "file://" reproduces the
        // RFC-8089 form a real OS launcher sends, and round-trips correctly through
        // normalizeFileUriArg on whatever OS this test runs on.
        val file = File("subscriptions.opml").absoluteFile
        val uriArg = "file://" + file.toURI().rawPath
        assertEquals(file.path, normalizeFileUriArg(uriArg))
    }

    @Test
    fun normalizesAFileUriArgWithLocalhostAuthorityToAPlainPath() {
        val file = File("subscriptions.opml").absoluteFile
        val uriArg = "file://localhost" + file.toURI().rawPath
        assertEquals(file.path, normalizeFileUriArg(uriArg))
    }

    @Test
    fun leavesNonFileUriArgsUnchanged() {
        assertEquals("keryx://oauth2/callback?code=abc", normalizeFileUriArg("keryx://oauth2/callback?code=abc"))
        assertEquals("/home/user/subscriptions.opml", normalizeFileUriArg("/home/user/subscriptions.opml"))
        assertEquals("--some-unrelated-flag", normalizeFileUriArg("--some-unrelated-flag"))
    }

    @Test
    fun macOsNeedsNoRuntimeRegistration() {
        assertEquals(UriSchemeRegistration.NONE, uriSchemeRegistrationFor("Mac OS X"))
    }

    @Test
    fun windowsUsesTheRegistry() {
        assertEquals(UriSchemeRegistration.WINDOWS, uriSchemeRegistrationFor("Windows 11"))
    }

    @Test
    fun linuxUsesDesktopEntries() {
        assertEquals(UriSchemeRegistration.LINUX, uriSchemeRegistrationFor("Linux"))
    }

    @Test
    fun anUnknownOsRegistersNothing() {
        assertEquals(UriSchemeRegistration.NONE, uriSchemeRegistrationFor("FreeBSD"))
    }

    @Test
    fun jpackageAppPathWinsOverTheProcessCommand() {
        assertEquals(
            "/opt/keryx/bin/Keryx",
            packagedLauncherPath("/opt/keryx/bin/Keryx", "/jdk/bin/java"),
        )
    }

    @Test
    fun fallsBackToTheProcessCommandWhenTheAppPathIsUnset() {
        assertEquals(
            "/opt/keryx/bin/Keryx",
            packagedLauncherPath(null, "/opt/keryx/bin/Keryx"),
        )
        assertEquals(
            "/opt/keryx/bin/Keryx",
            packagedLauncherPath("", "/opt/keryx/bin/Keryx"),
        )
    }

    @Test
    fun gradleRunIsNotRegistered() {
        // The JVM's own binary must never become the keryx:// handler — the registration would
        // outlive the Gradle run and hand OAuth callbacks to a bare JVM.
        assertNull(packagedLauncherPath(null, "/Users/x/.gradle/jdks/jdk-25/bin/java"))
        assertNull(packagedLauncherPath(null, "/usr/lib/jvm/jdk-25/bin/javaw"))
        assertNull(packagedLauncherPath(null, """C:\Program Files\Java\jdk-25\bin\java.exe"""))
        assertNull(packagedLauncherPath(null, """C:\Program Files\Java\jdk-25\bin\javaw.exe"""))
    }

    @Test
    fun noLauncherPathAtAll() {
        assertNull(packagedLauncherPath(null, null))
        assertNull(packagedLauncherPath(null, ""))
    }

    @Test
    fun windowsRegistrationWritesUnderTheCurrentUserHive() {
        val recordedCommands = mutableListOf<List<String>>()
        var importedRegFileText: String? = null
        registerWindowsUriScheme(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") { command ->
            recordedCommands.add(command)
            if (command.first() == "reg.exe" && command.getOrNull(1) == "import") {
                importedRegFileText = regFileTextFor(command)
            }
            0
        }

        assertEquals(3, recordedCommands.size)
        for (command in recordedCommands) {
            assertTrue(command.none { it.contains("HKEY_CLASSES_ROOT") })
        }
        assertTrue(
            recordedCommands.take(2).all { it.any { arg -> arg.contains("HKEY_CURRENT_USER\\Software\\Classes\\keryx") } },
        )
        // The shell\open\command write goes through a .reg file import (see buildShellOpenCommandImport) —
        // ProcessBuilder's Windows quoting mishandles a "\"<path>\" \"%1\"" argument passed directly.
        val regText = checkNotNull(importedRegFileText) { "expected a reg.exe import command" }
        assertTrue(regText.contains("[HKEY_CURRENT_USER\\Software\\Classes\\keryx\\shell\\open\\command]"))
        assertTrue(regText.contains("@=\"\\\"C:\\\\Program Files\\\\Keryx\\\\Keryx.exe\\\" \\\"%1\\\"\""))
    }

    @Test
    fun windowsRegistrationSurvivesANonZeroExitCode() {
        // A failed reg.exe must not throw — it's swallowed and logged, not fatal to startup.
        var callCount = 0
        registerWindowsUriScheme(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") {
            callCount++
            5
        }
        assertEquals(3, callCount)
    }

    @Test
    fun windowsOpmlAssociationWritesUnderTheCurrentUserHiveViaADedicatedProgId() {
        val recordedCommands = mutableListOf<List<String>>()
        var importedRegFileText: String? = null
        registerWindowsOpmlAssociation(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") { command ->
            recordedCommands.add(command)
            if (command.first() == "reg.exe" && command.getOrNull(1) == "import") {
                importedRegFileText = regFileTextFor(command)
            }
            0
        }

        assertEquals(3, recordedCommands.size)
        for (command in recordedCommands) {
            assertTrue(command.none { it.contains("HKEY_CLASSES_ROOT") })
        }
        assertTrue(
            recordedCommands.any { it.contains("HKEY_CURRENT_USER\\Software\\Classes\\.opml") },
        )
        assertTrue(
            recordedCommands.any { command -> command.any { it == "Keryx.opml" } },
        )
        val regText = checkNotNull(importedRegFileText) { "expected a reg.exe import command" }
        assertTrue(regText.contains("[HKEY_CURRENT_USER\\Software\\Classes\\Keryx.opml\\shell\\open\\command]"))
        assertTrue(regText.contains("@=\"\\\"C:\\\\Program Files\\\\Keryx\\\\Keryx.exe\\\" \\\"%1\\\"\""))
    }

    @Test
    fun windowsOpmlAssociationSurvivesANonZeroExitCode() {
        var callCount = 0
        registerWindowsOpmlAssociation(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") {
            callCount++
            5
        }
        assertEquals(3, callCount)
    }
}
