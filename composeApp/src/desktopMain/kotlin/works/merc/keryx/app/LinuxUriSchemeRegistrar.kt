package works.merc.keryx.app

import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.OAUTH_CUSTOM_URI_REDIRECT
import works.merc.keryx.app.core.UPDATE_DESKTOP_DATABASE_TIMEOUT_MS
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "UriScheme"

/** `keryx`, derived from the redirect URI so the scheme and the redirect can never drift apart. */
internal val CUSTOM_URI_SCHEME: String = OAUTH_CUSTOM_URI_REDIRECT.substringBefore("://")

/** The XDG MIME type that stands for "handler for `keryx://` URIs". */
internal val CUSTOM_URI_MIME_TYPE: String = "x-scheme-handler/$CUSTOM_URI_SCHEME"

/**
 * Deliberately not the name a deb/rpm menu entry would use. If the two shared a name, uninstalling
 * the package would leave this user-level copy behind, pointing at a binary that no longer exists.
 */
internal const val URI_HANDLER_DESKTOP_FILE = "keryx-url-handler.desktop"

private const val DEFAULT_APPLICATIONS = "Default Applications"
private const val ADDED_ASSOCIATIONS = "Added Associations"

/** `$envName` when set, else `$HOME/$homeRelativeFallback` — the XDG base-directory convention. */
internal fun xdgDir(envName: String, homeRelativeFallback: String): File =
    System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), homeRelativeFallback)

/** Runs [command], aborting and throwing [IOException] if it outlives [timeoutMillis]. */
internal fun runProcessWithTimeout(command: List<String>, timeoutMillis: Long) {
    val proc = ProcessBuilder(command).start()
    if (!proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        proc.destroyForcibly()
        throw IOException("${command.first()} timed out")
    }
}

/**
 * Teaches the desktop environment to route `keryx://` URIs to the app, which is what makes the
 * OAuth callback reach [main] as an argv entry on Linux (macOS uses Info.plist, Windows the
 * registry). Without it the browser cannot resolve the redirect at all and reports an unknown
 * protocol, so cloud linking silently times out.
 *
 * Two files are written, both under the user's own home rather than system-wide (no root needed):
 * the `.desktop` entry declaring `MimeType=x-scheme-handler/keryx;` and — crucially — an `Exec`
 * line ending in `%u`, without which the desktop entry spec does not pass the URI to the process;
 * and a `mimeapps.list` association naming that entry as the handler.
 *
 * All of [applicationsDir], [mimeAppsList] and [refreshDesktopDatabase] are injectable so tests
 * never touch the real user's configuration.
 */
internal class LinuxUriSchemeRegistrar(
    private val launcherPath: String,
    private val applicationsDir: File = xdgDir("XDG_DATA_HOME", ".local/share").resolve("applications"),
    private val mimeAppsList: File = xdgDir("XDG_CONFIG_HOME", ".config").resolve("mimeapps.list"),
    private val refreshDesktopDatabase: (File) -> Unit = { dir ->
        runProcessWithTimeout(listOf("update-desktop-database", dir.path), UPDATE_DESKTOP_DATABASE_TIMEOUT_MS)
    },
) {
    /** Returns true when the handler is registered; already being up to date counts as success. */
    fun register(): Boolean = runCatching {
        val desktopFile = File(applicationsDir, URI_HANDLER_DESKTOP_FILE)
        val entry = desktopEntryContent(launcherPath)
        var changed = false

        if (readOrNull(desktopFile) != entry) {
            writeAtomically(desktopFile, entry)
            changed = true
        }

        val existingAssociations = readOrNull(mimeAppsList)
        val mergedAssociations =
            mergeMimeAppsList(existingAssociations, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        if (mergedAssociations != existingAssociations) {
            writeAtomically(mimeAppsList, mergedAssociations)
            changed = true
        }

        if (changed) {
            // Best-effort only. The `[Default Applications]` entry above is what GIO, KDE and
            // xdg-open's generic path all consult first; refreshing mimeinfo.cache merely keeps
            // the "which apps can open this" list accurate, so a missing desktop-file-utils is
            // not a failure. Skipped when nothing changed, so startup spawns no process at all.
            runCatching { refreshDesktopDatabase(applicationsDir) }.onFailure {
                Log.info(LOG_TAG, "Could not refresh the desktop database; the association itself is still in place")
            }
            Log.info(LOG_TAG, "Registered keryx:// URI scheme handler at ${desktopFile.path}")
        }
        true
    }.getOrElse {
        Log.warn(LOG_TAG, "Could not register the Linux keryx:// URI scheme", it)
        false
    }

    private fun readOrNull(file: File): String? = file.takeIf { it.isFile }?.readText()

    /**
     * Writes via a temp file and an atomic rename. `mimeapps.list` is shared with other
     * applications and the desktop environment, so a concurrent reader must never observe a
     * half-written file. Mirrors how `SingleInstanceCoordinator` publishes `keryx.port`.
     */
    private fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        tmp.writeText(content)
        Files.move(
            tmp.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

/**
 * The `.desktop` entry that registers the app as the `keryx://` handler.
 *
 * `NoDisplay=true` keeps it out of the application menu — it exists purely to own the scheme, and
 * hiding it means it can never show up as a duplicate next to a packaged menu entry.
 * `StartupNotify=false` stops the desktop environment from showing a "launch failed" cursor for a
 * process that hands the URI to the running instance and exits immediately.
 *
 * `Name` is the product name ([APP_NAME]), not user-facing copy, so it does not belong in
 * `strings.xml`.
 */
internal fun desktopEntryContent(launcherPath: String): String =
    """
    [Desktop Entry]
    Type=Application
    Name=$APP_NAME
    Exec=${escapeDesktopExecPath(launcherPath)} %u
    Terminal=false
    NoDisplay=true
    StartupNotify=false
    MimeType=$CUSTOM_URI_MIME_TYPE;
    """.trimIndent() + "\n"

/**
 * Quotes a launcher path for an `Exec=` line. The desktop entry spec applies two layers of
 * escaping: reserved characters inside a quoted argument are backslash-escaped (and a literal
 * `%` is doubled so it is not read as a field code), then backslashes in the resulting value are
 * doubled again by the general value-escaping rule. Paths containing spaces — an app image built
 * under a user directory, say — are the case that matters in practice.
 */
internal fun escapeDesktopExecPath(path: String): String {
    val quoted = buildString {
        append('"')
        for (char in path) {
            when (char) {
                '"', '`', '$', '\\' -> append('\\').append(char)
                '%' -> append("%%")
                else -> append(char)
            }
        }
        append('"')
    }
    return quoted.replace("\\", "\\\\")
}

/**
 * Returns [existing] with [mimeType] associated to [desktopFileName], or [existing] unchanged when
 * the association is already in place — which is what makes registration idempotent.
 *
 * `mimeapps.list` belongs to the user and to every other application on the system, so every line
 * this does not own — comments, blank lines, other MIME types, other sections, the presence or
 * absence of a trailing newline — is preserved exactly.
 *
 * `[Default Applications]` is the entry that actually resolves the scheme; an existing value is
 * replaced because no other application can meaningfully claim `keryx://`. `[Added Associations]`
 * is a belt-and-braces addition for environments where `mimeinfo.cache` is never generated.
 */
internal fun mergeMimeAppsList(existing: String?, desktopFileName: String, mimeType: String): String {
    val endsWithNewline = existing.isNullOrEmpty() || existing.endsWith("\n")
    val lines = if (existing.isNullOrEmpty()) {
        mutableListOf()
    } else {
        existing.removeSuffix("\n").split("\n").toMutableList()
    }

    upsertEntry(lines, DEFAULT_APPLICATIONS, mimeType) { desktopFileName }
    upsertEntry(lines, ADDED_ASSOCIATIONS, mimeType) { current ->
        val values = current.orEmpty().split(";").filter { it.isNotBlank() }
        if (desktopFileName in values) null else (values + desktopFileName).joinToString(";", postfix = ";")
    }

    val merged = lines.joinToString("\n")
    return if (endsWithNewline) "$merged\n" else merged
}

/**
 * Sets [key] within [section] to whatever [newValue] returns, creating the section or the key if
 * needed. [newValue] receives the current value (null when the key is absent) and may return null
 * to leave [lines] untouched.
 */
private fun upsertEntry(
    lines: MutableList<String>,
    section: String,
    key: String,
    newValue: (current: String?) -> String?,
) {
    val header = "[$section]"
    val sectionStart = lines.indexOfFirst { it.trim() == header }
    if (sectionStart < 0) {
        val value = newValue(null) ?: return
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add(header)
        lines.add("$key=$value")
        return
    }

    val sectionEnd = (sectionStart + 1 until lines.size)
        .firstOrNull { lines[it].trimStart().startsWith("[") }
        ?: lines.size
    val keyIndex = (sectionStart + 1 until sectionEnd)
        .firstOrNull { lines[it].contains('=') && lines[it].substringBefore('=').trim() == key }

    if (keyIndex != null) {
        val value = newValue(lines[keyIndex].substringAfter('=')) ?: return
        lines[keyIndex] = "$key=$value"
    } else {
        val value = newValue(null) ?: return
        // Insert after the section's last non-blank line so any blank separator stays in place.
        var insertAt = sectionEnd
        while (insertAt > sectionStart + 1 && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, "$key=$value")
    }
}
