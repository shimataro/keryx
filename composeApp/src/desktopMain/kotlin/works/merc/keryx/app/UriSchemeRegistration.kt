package works.merc.keryx.app

import works.merc.keryx.app.core.Log

private const val LOG_TAG = "UriScheme"

/** How the OS learns about the `keryx://` scheme on a given platform. */
internal enum class UriSchemeRegistration {
    /** macOS — declared in Info.plist (`CFBundleURLTypes`) at packaging time; nothing to do at runtime. */
    NONE,

    /** Windows — `HKEY_CLASSES_ROOT\keryx` registry keys written at startup. */
    WINDOWS,

    /** Linux — a user-level `.desktop` entry plus a `mimeapps.list` association written at startup. */
    LINUX,
}

/** Pure OS dispatch, kept separate from [registerCustomUriScheme] so it can be unit-tested. */
internal fun uriSchemeRegistrationFor(osName: String): UriSchemeRegistration {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> UriSchemeRegistration.NONE
        os.contains("win") -> UriSchemeRegistration.WINDOWS
        os.contains("linux") -> UriSchemeRegistration.LINUX
        else -> UriSchemeRegistration.NONE
    }
}

/** Launcher names that mean "this is a plain JVM run", not a packaged app. */
private val JVM_LAUNCHER_NAMES = setOf("java", "javaw", "java.exe", "javaw.exe")

/**
 * Absolute path of the packaged app launcher, or null when the current process is not one.
 *
 * `jpackage` launchers set `jpackage.app-path` to their own path, which is both the value we
 * want and a positive signal that we are running from a packaged app. It is not part of the
 * documented jpackage contract, so we fall back to the process command — for a jpackage
 * app-image that is still the launcher itself (it loads libjvm in-process rather than forking
 * `java`).
 *
 * Returns null under `./gradlew :composeApp:run`, where the process command is the JDK's own
 * `java` binary. Registering that as the `keryx://` handler would outlive the Gradle run and
 * leave the OS handing OAuth callbacks to a bare JVM.
 */
internal fun packagedLauncherPath(
    appPathProperty: String? = System.getProperty("jpackage.app-path"),
    processCommand: String? = currentExecutablePath(),
): String? {
    appPathProperty?.takeIf { it.isNotBlank() }?.let { return it }
    val command = processCommand?.takeIf { it.isNotBlank() } ?: return null
    // Paths are '\'-separated on Windows and '/'-separated elsewhere; accept both.
    val name = command.substringAfterLast('/').substringAfterLast('\\')
    return if (name.lowercase() in JVM_LAUNCHER_NAMES) null else command
}

/**
 * Registers the `keryx://` URL scheme with the OS so browsers can redirect the OAuth callback
 * back to the app. macOS needs nothing here (Info.plist covers it); the other two platforms
 * only get registered when running from a packaged launcher — see [packagedLauncherPath].
 */
internal fun registerCustomUriScheme() {
    val launcherPath = packagedLauncherPath()
    if (launcherPath == null) {
        Log.info(LOG_TAG, "Not running from a packaged launcher; skipping keryx:// scheme registration")
        return
    }
    when (uriSchemeRegistrationFor(System.getProperty("os.name") ?: "")) {
        UriSchemeRegistration.NONE -> Unit
        UriSchemeRegistration.WINDOWS -> registerWindowsUriScheme(launcherPath)
        UriSchemeRegistration.LINUX -> LinuxUriSchemeRegistrar(launcherPath).register()
    }
}

/** Registers the keryx:// URL scheme in the Windows registry so browsers can redirect back to the app. */
private fun registerWindowsUriScheme(launcherPath: String) {
    runCatching {
        val reg = "reg.exe"
        ProcessBuilder(reg, "add", "HKEY_CLASSES_ROOT\\keryx", "/ve", "/d", "URL:keryx Protocol", "/f").start().waitFor()
        ProcessBuilder(reg, "add", "HKEY_CLASSES_ROOT\\keryx", "/v", "URL Protocol", "/d", "", "/f").start().waitFor()
        ProcessBuilder(reg, "add", "HKEY_CLASSES_ROOT\\keryx\\shell\\open\\command", "/ve", "/d", "\"$launcherPath\" \"%1\"", "/f").start().waitFor()
    }.onFailure { Log.warn(LOG_TAG, "Could not register Windows URI scheme", it) }
}
