package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.REG_EXE_TIMEOUT_MS
import works.merc.keryx.app.platform.osName
import java.io.File

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
    val (importCommand, regFile) = buildShellOpenCommandImport(
        "HKEY_CURRENT_USER\\Software\\Classes\\keryx\\shell\\open\\command",
        launcherPath,
    )
    val commands = listOf(
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\keryx", "/ve", "/d", "URL:keryx Protocol", "/f"),
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\keryx", "/v", "URL Protocol", "/d", "", "/f"),
        importCommand,
    )
    try {
        runRegistryCommands(commands, "Windows URI scheme", runCommand)
    } finally {
        regFile.delete()
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
    val (importCommand, regFile) = buildShellOpenCommandImport(
        "HKEY_CURRENT_USER\\Software\\Classes\\$progId\\shell\\open\\command",
        launcherPath,
    )
    val commands = listOf(
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\.opml", "/ve", "/d", progId, "/f"),
        listOf(reg, "add", "HKEY_CURRENT_USER\\Software\\Classes\\$progId", "/ve", "/d", "OPML Document", "/f"),
        importCommand,
    )
    try {
        runRegistryCommands(commands, "Windows .opml association", runCommand)
    } finally {
        regFile.delete()
    }
}

/**
 * Builds the `reg.exe import <tempfile>` command that writes [launcherPath] as the default value
 * of [commandKey] (a `...\shell\open\command` key), via a temporary `.reg` file rather than
 * `reg.exe add ... /d "\"<path>\" \"%1\""` directly.
 *
 * `ProcessBuilder`'s Windows argument encoding has a long-standing legacy heuristic (JDK-7032109 /
 * JDK-8250568 / JDK-8282989): a command-line argument whose first *and* last characters are both
 * `"` is treated as "already quoted" and passed through without escaping its interior. The `/d`
 * value needed here — `"<launcherPath>" "%1"` — starts and ends with `"`, so it hits exactly that
 * heuristic: the embedded `" "` in the middle reaches the actual Windows command line unescaped,
 * and `reg.exe`'s own argv parser then splits it into two separate arguments instead of one, which
 * `reg.exe add /d` rejects (a silent `exit 1`, logged by [runRegistryCommands] with no detail since
 * `reg.exe`'s stderr isn't captured). A `.reg` file has its own, unambiguous escaping syntax that
 * this code controls directly, sidestepping `ProcessBuilder`'s Windows command-line reconstruction
 * — and therefore that heuristic — entirely.
 *
 * @return The `reg.exe import` command to run, paired with the temp file it reads from (the caller
 * deletes it once the command has run).
 */
private fun buildShellOpenCommandImport(commandKey: String, launcherPath: String): Pair<List<String>, File> {
    val escapedPath = launcherPath.replace("\\", "\\\\").replace("\"", "\\\"")
    val regFileText = "Windows Registry Editor Version 5.00\r\n\r\n[$commandKey]\r\n@=\"\\\"$escapedPath\\\" \\\"%1\\\"\"\r\n"
    val tempFile = File.createTempFile("keryx-uri-scheme-", ".reg")
    // reg.exe needs a UTF-16LE .reg file with a BOM to parse non-ASCII content correctly — the
    // install path can contain non-ASCII characters, since packageMsi's dirChooser lets the user
    // pick any install directory.
    tempFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + regFileText.toByteArray(Charsets.UTF_16LE))
    return listOf("reg.exe", "import", tempFile.path) to tempFile
}

/**
 * Runs each of [commands] via [runCommand] (a `reg.exe` invocation), logging a warning for a
 * non-zero exit code or a failure to run it at all — shared by [registerWindowsUriScheme] and
 * [registerWindowsOpmlAssociation], which differ only in which registry keys they write.
 *
 * @param failureContext Describes what registration this is, for the failure log message.
 */
private fun runRegistryCommands(commands: List<List<String>>, failureContext: String, runCommand: (List<String>) -> Int) {
    for (command in commands) {
        runCatching { runCommand(command) }
            .onSuccess { exitCode ->
                if (exitCode != 0) {
                    Log.warn(LOG_TAG, "reg.exe exited with $exitCode for: ${command.joinToString(" ")}")
                }
            }
            .onFailure { Log.warn(LOG_TAG, "Could not register $failureContext", it) }
    }
}
