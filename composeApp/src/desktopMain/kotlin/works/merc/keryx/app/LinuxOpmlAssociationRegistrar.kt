package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.UPDATE_DESKTOP_DATABASE_TIMEOUT_MS
import java.io.File

private const val LOG_TAG = "OpmlAssociation"

/** The MIME type Keryx registers itself as the handler for `.opml` files. */
internal const val OPML_MIME_TYPE = "application/x-opml+xml"

/**
 * No MIME type for OPML is defined in the base shared-mime-info database, and the third-party
 * feed-reader ecosystem never converged on one — this string is the other candidate seen in the
 * wild besides [OPML_MIME_TYPE] (the overly generic `application/xml`/`text/xml` are deliberately
 * not included here, since claiming those would make Keryx an "Open With" candidate for any XML
 * file, not just OPML ones) — the same underlying situation as the competing macOS UTIs (see the
 * `.opml` section in `docs/build.md`). Listed only in [OPML_DESKTOP_MIME_TYPES] (not given its own
 * glob in [opmlMimePackageContent]) so Keryx becomes an eligible opener if another already-installed
 * reader's shared-mime-info package has already bound `.opml` to it, without Keryx itself
 * introducing a second, conflicting glob-to-type mapping for `.opml`.
 */
internal const val OPML_MIME_TYPE_ALT = "text/x-opml"

/** MIME types Keryx declares itself able to open, in the `.desktop` entry's `MimeType=` list. */
internal val OPML_DESKTOP_MIME_TYPES = listOf(OPML_MIME_TYPE, OPML_MIME_TYPE_ALT)

/**
 * Deliberately not the name a deb/rpm menu entry would use — same reasoning as
 * [URI_HANDLER_DESKTOP_FILE]: sharing a name with a packaged entry would leave this user-level copy
 * behind (pointing at a removed binary) after an uninstall.
 */
internal const val OPML_HANDLER_DESKTOP_FILE = "keryx-opml-handler.desktop"

/** The shared-mime-info package file name that maps the `.opml` extension to [OPML_MIME_TYPE]. */
internal const val OPML_MIME_PACKAGE_FILE = "keryx-opml.xml"

/**
 * Teaches the desktop environment that Keryx opens `.opml` files, so double-clicking one (or
 * choosing "Open With") in a file manager launches Keryx with the file's path as its argument (see
 * [classifyLaunchArg] / `main.kt`).
 *
 * Three files are written, all under the user's own home rather than system-wide (no root needed):
 * a `.desktop` entry declaring `MimeType=` with both [OPML_DESKTOP_MIME_TYPES] and an `Exec` line
 * ending in `%f` (a bare local path, not a URI — matches how [classifyLaunchArg] expects to
 * receive it); a shared-mime-info package XML mapping the `*.opml` glob to [OPML_MIME_TYPE] only
 * (not guaranteed to be predefined by the distro's own `shared-mime-info` package, unlike common
 * formats — see [OPML_MIME_TYPE_ALT] for why the second MIME type isn't also given a glob here);
 * and a `mimeapps.list` association naming that desktop entry as the (default) handler for each of
 * [OPML_DESKTOP_MIME_TYPES].
 *
 * All of [applicationsDir], [mimeAppsList], [mimePackagesDir], [refreshDesktopDatabase] and
 * [refreshMimeDatabase] are injectable so tests never touch the real user's configuration.
 */
internal class LinuxOpmlAssociationRegistrar(
    private val launcherPath: String,
    private val applicationsDir: File = xdgDir("XDG_DATA_HOME", ".local/share").resolve("applications"),
    private val mimeAppsList: File = xdgDir("XDG_CONFIG_HOME", ".config").resolve("mimeapps.list"),
    private val mimePackagesDir: File = xdgDir("XDG_DATA_HOME", ".local/share").resolve("mime/packages"),
    private val refreshDesktopDatabase: (File) -> Unit = { dir ->
        runProcessWithTimeout(listOf("update-desktop-database", dir.path), UPDATE_DESKTOP_DATABASE_TIMEOUT_MS)
    },
    private val refreshMimeDatabase: (File) -> Unit = { mimeDir ->
        runProcessWithTimeout(listOf("update-mime-database", mimeDir.path), UPDATE_DESKTOP_DATABASE_TIMEOUT_MS)
    },
) {
    /**
     * Registers the Linux handler for `.opml` files.
     *
     * @return `true` if registration succeeds or is already up to date, `false` if registration fails.
     */
    fun register(): Boolean = runCatching {
        val desktopFile = File(applicationsDir, OPML_HANDLER_DESKTOP_FILE)
        val entry = desktopEntryContent(launcherPath, OPML_DESKTOP_MIME_TYPES.joinToString(";"), "%f")
        var changed = false

        if (readOrNull(desktopFile) != entry) {
            writeAtomically(desktopFile, entry)
            changed = true
        }

        val mimePackageFile = File(mimePackagesDir, OPML_MIME_PACKAGE_FILE)
        val mimePackage = opmlMimePackageContent()
        var mimeDatabaseChanged = false
        if (readOrNull(mimePackageFile) != mimePackage) {
            writeAtomically(mimePackageFile, mimePackage)
            changed = true
            mimeDatabaseChanged = true
        }

        val existingAssociations = readOrNull(mimeAppsList)
        var mergedAssociations = existingAssociations.orEmpty()
        for (mimeType in OPML_DESKTOP_MIME_TYPES) {
            mergedAssociations = mergeMimeAppsList(mergedAssociations, OPML_HANDLER_DESKTOP_FILE, mimeType)
        }
        if (mergedAssociations != existingAssociations) {
            writeAtomically(mimeAppsList, mergedAssociations)
            changed = true
        }

        if (mimeDatabaseChanged) {
            // Not best-effort, unlike the desktop-database refresh below: application/x-opml+xml is a
            // custom MIME type the OS doesn't already know, and file-type lookup only consults the
            // compiled shared-mime-info cache (globs2, etc.) that this command rebuilds — never the raw
            // package XML directly. If it never succeeds, the *.opml mapping never reaches the cache and
            // the whole association silently never matches, so a failure here must abort registration
            // (propagates to the outer runCatching below) rather than being swallowed. The package file
            // is also rolled back on failure, since otherwise a later register() call would see its
            // content already matching, skip the refresh as "unchanged", and report success while the
            // cache is still stale.
            runCatching { refreshMimeDatabase(mimePackagesDir.parentFile) }
                .onFailure { mimePackageFile.delete() }
                .getOrThrow()
        }
        if (changed) {
            // Best-effort only — see the equivalent comment in LinuxUriSchemeRegistrar.register().
            runCatching { refreshDesktopDatabase(applicationsDir) }.onFailure {
                Log.info(LOG_TAG, "Could not refresh the desktop database; the association itself is still in place")
            }
            Log.info(LOG_TAG, "Registered .opml file association at ${desktopFile.path}")
        }
        true
    }.getOrElse {
        Log.warn(LOG_TAG, "Could not register the Linux .opml file association", it)
        false
    }
}

/**
 * Generates the shared-mime-info package XML mapping the `*.opml` glob to [OPML_MIME_TYPE]. Not
 * every distro's `shared-mime-info` package predefines this type, so Keryx ships its own mapping
 * rather than relying on it being present.
 */
internal fun opmlMimePackageContent(): String =
    """
    <?xml version="1.0" encoding="UTF-8"?>
    <mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
        <mime-type type="$OPML_MIME_TYPE">
            <comment>OPML document</comment>
            <glob pattern="*.opml"/>
        </mime-type>
    </mime-info>
    """.trimIndent() + "\n"
