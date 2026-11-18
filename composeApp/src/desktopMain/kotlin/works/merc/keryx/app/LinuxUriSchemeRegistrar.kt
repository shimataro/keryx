package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.OAUTH_CUSTOM_URI_REDIRECT
import works.merc.keryx.app.core.UPDATE_DESKTOP_DATABASE_TIMEOUT_MS
import java.io.File

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

/**
 * Selects an XDG base directory from an environment variable or a user-home fallback.
 *
 * @param envName The environment variable containing the directory path.
 * @param homeRelativeFallback The path relative to the user's home directory when the environment variable is unset or blank.
 * @return The selected base directory.
 */
internal fun xdgDir(envName: String, homeRelativeFallback: String): File =
    System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), homeRelativeFallback)

/** The user's own `applications` directory (`$XDG_DATA_HOME/applications`), where both registrars write their `.desktop` entry. */
internal fun defaultApplicationsDir(): File = xdgDir("XDG_DATA_HOME", ".local/share").resolve("applications")

/** The user's own `mimeapps.list` (`$XDG_CONFIG_HOME/mimeapps.list`), where both registrars record their association. */
internal fun defaultMimeAppsList(): File = xdgDir("XDG_CONFIG_HOME", ".config").resolve("mimeapps.list")

/**
 * Runs [refreshDesktopDatabase] on [applicationsDir], logging under [logTag] rather than failing.
 *
 * Best-effort only. The `[Default Applications]` entry is what GIO, KDE and xdg-open's generic path
 * all consult first; refreshing mimeinfo.cache merely keeps the "which apps can open this" list
 * accurate, so a missing desktop-file-utils is not a failure.
 */
internal fun refreshDesktopDatabaseBestEffort(refreshDesktopDatabase: (File) -> Unit, applicationsDir: File, logTag: String) {
    runCatching { refreshDesktopDatabase(applicationsDir) }.onFailure {
        Log.info(logTag, "Could not refresh the desktop database; the association itself is still in place")
    }
}

/** Default `update-desktop-database` refresh, shared by [LinuxUriSchemeRegistrar] and [LinuxOpmlAssociationRegistrar]. */
internal fun defaultRefreshDesktopDatabase(): (File) -> Unit = { dir ->
    runProcessWithTimeout(listOf("update-desktop-database", dir.path), UPDATE_DESKTOP_DATABASE_TIMEOUT_MS)
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
    private val applicationsDir: File = defaultApplicationsDir(),
    private val mimeAppsList: File = defaultMimeAppsList(),
    private val refreshDesktopDatabase: (File) -> Unit = defaultRefreshDesktopDatabase(),
) {
    /**
     * Registers the Linux handler for the custom URI scheme.
     *
     * @return `true` if registration succeeds or is already up to date, `false` if registration fails.
     */
    fun register(): Boolean = runCatching {
        val desktopFile = File(applicationsDir, URI_HANDLER_DESKTOP_FILE)
        val entry = desktopEntryContent(launcherPath, CUSTOM_URI_MIME_TYPE, "%u")
        val desktopFileChanged = writeIfChanged(desktopFile, entry)

        val existingAssociations = readOrNull(mimeAppsList)
        val mergedAssociations =
            mergeMimeAppsList(existingAssociations, URI_HANDLER_DESKTOP_FILE, CUSTOM_URI_MIME_TYPE)
        // Both writes always run (separate vals, never a short-circuiting ||).
        val associationsChanged = writeIfChanged(mimeAppsList, mergedAssociations)

        if (desktopFileChanged || associationsChanged) {
            // Skipped when nothing changed, so startup spawns no process at all.
            refreshDesktopDatabaseBestEffort(refreshDesktopDatabase, applicationsDir, LOG_TAG)
            Log.info(LOG_TAG, "Registered keryx:// URI scheme handler at ${desktopFile.path}")
        }
        true
    }.getOrElse {
        Log.warn(LOG_TAG, "Could not register the Linux keryx:// URI scheme", it)
        false
    }
}
