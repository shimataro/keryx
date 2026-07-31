package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.REG_EXE_TIMEOUT_MS
import works.merc.keryx.app.platform.osName

private const val LOG_TAG = "UriScheme"

/** How the OS learns about the `keryx://` scheme on a given platform. */
internal enum class UriSchemeRegistration {
    /** macOS — declared in Info.plist (`CFBundleURLTypes`) at packaging time; nothing to do at runtime. */
    NONE,

    /** Windows — `HKEY_CURRENT_USER\Software\Classes\keryx` registry keys written at startup. */
    WINDOWS,

    /** Linux — a user-level `.desktop` entry plus a `mimeapps.list` association written at startup. */
    LINUX,
}

/**
 * Determines the URI scheme registration mechanism for an operating system name.
 *
 * @param osName The operating system name to classify.
 * @return The registration mechanism associated with the operating system.
 */
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
 * Resolves the packaged application launcher path.
 *
 * @param appPathProperty The packaged launcher path reported by `jpackage`.
 * @param processCommand The current process executable path used as a fallback.
 * @return The launcher path, or `null` when the process is unavailable or uses a plain JVM launcher.
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
 * Registers the `keryx://` URI scheme and `.opml` file association when running from a packaged
 * launcher.
 */
internal fun registerFileAssociations() {
    val launcherPath = packagedLauncherPath()
    if (launcherPath == null) {
        Log.info(LOG_TAG, "Not running from a packaged launcher; skipping keryx:// scheme and .opml association registration")
        return
    }
    when (uriSchemeRegistrationFor(osName)) {
        UriSchemeRegistration.NONE -> Unit
        UriSchemeRegistration.WINDOWS -> {
            registerWindowsUriScheme(launcherPath)
            registerWindowsOpmlAssociation(launcherPath)
        }
        UriSchemeRegistration.LINUX -> {
            LinuxUriSchemeRegistrar(launcherPath).register()
            LinuxOpmlAssociationRegistrar(launcherPath).register()
        }
    }
}

/**
 * Registers the `keryx://` URL scheme for the current Windows user.
 *
 * @param launcherPath The path to the packaged application launcher.
 */
internal fun registerWindowsUriScheme(
    launcherPath: String,
    runCommand: (List<String>) -> Int = { args -> runProcessWithTimeout(args, REG_EXE_TIMEOUT_MS) },
) {
    val reg = "reg.exe"
    val commands = listOf(
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\keryx", "/ve", "/d", "URL:keryx Protocol", "/f"),
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\keryx", "/v", "URL Protocol", "/d", "", "/f"),
        listOf(
            reg,
            "add",
            "HKEY_CURRENT_USER\\Software\\Classes\\keryx\\shell\\open\\command",
            "/ve",
            "/d",
            "\"$launcherPath\" \"%1\"",
            "/f",
        ),
    )
    for (command in commands) {
        runCatching { runCommand(command) }
            .onSuccess { exitCode ->
                if (exitCode != 0) {
                    Log.warn(LOG_TAG, "reg.exe exited with $exitCode for: ${command.joinToString(" ")}")
                }
            }
            .onFailure { Log.warn(LOG_TAG, "Could not register Windows URI scheme", it) }
    }
}

/**
 * Registers Keryx as the `.opml` file association for the current Windows user, via a dedicated
 * `Keryx.opml` ProgID (rather than writing directly under `.opml`, which would collide with any
 * other app's own ProgID for the same extension).
 *
 * @param launcherPath The path to the packaged application launcher.
 */
internal fun registerWindowsOpmlAssociation(
    launcherPath: String,
    runCommand: (List<String>) -> Int = { args -> runProcessWithTimeout(args, REG_EXE_TIMEOUT_MS) },
) {
    val reg = "reg.exe"
    val progId = "Keryx.opml"
    val commands = listOf(
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\.opml", "/ve", "/d", progId, "/f"),
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\$progId", "/ve", "/d", "OPML Document", "/f"),
        listOf(
            reg,
            "add",
            "HKEY_CURRENT_USER\\Software\\Classes\\$progId\\shell\\open\\command",
            "/ve",
            "/d",
            "\"$launcherPath\" \"%1\"",
            "/f",
        ),
    )
    for (command in commands) {
        runCatching { runCommand(command) }
            .onSuccess { exitCode ->
                if (exitCode != 0) {
                    Log.warn(LOG_TAG, "reg.exe exited with $exitCode for: ${command.joinToString(" ")}")
                }
            }
            .onFailure { Log.warn(LOG_TAG, "Could not register Windows .opml association", it) }
    }
}
