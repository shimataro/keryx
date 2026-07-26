package works.merc.keryx.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
