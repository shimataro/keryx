package works.merc.keryx.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UriSchemeRegistrationTest {
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
        registerWindowsUriScheme(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") { command ->
            recordedCommands.add(command)
            0
        }

        assertEquals(3, recordedCommands.size)
        for (command in recordedCommands) {
            assertTrue(command.any { it.contains("HKEY_CURRENT_USER\\Software\\Classes\\keryx") })
            assertTrue(command.none { it.contains("HKEY_CLASSES_ROOT") })
        }
        assertTrue(
            recordedCommands.any { it.contains("HKEY_CURRENT_USER\\Software\\Classes\\keryx\\shell\\open\\command") },
        )
        assertTrue(
            recordedCommands.any { command -> command.any { it == "\"C:\\Program Files\\Keryx\\Keryx.exe\" \"%1\"" } },
        )
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
        registerWindowsOpmlAssociation(launcherPath = "C:\\Program Files\\Keryx\\Keryx.exe") { command ->
            recordedCommands.add(command)
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
        assertTrue(
            recordedCommands.any { it.contains("HKEY_CURRENT_USER\\Software\\Classes\\Keryx.opml\\shell\\open\\command") },
        )
        assertTrue(
            recordedCommands.any { command -> command.any { it == "\"C:\\Program Files\\Keryx\\Keryx.exe\" \"%1\"" } },
        )
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
