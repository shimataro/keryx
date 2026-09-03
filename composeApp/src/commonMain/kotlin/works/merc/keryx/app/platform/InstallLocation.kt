package works.merc.keryx.app.platform

/**
 * How this running build was installed, as far as an in-app update can act on it. Distinct from
 * [selfUpdateCheckSupported] (which only gates whether the *check* is offered at all, e.g. hiding
 * it on a Play-installed Android build): this describes what an *approved* update should actually
 * do — replace files in place, hand off to the OS installer, or fall back to the release page.
 */
enum class InstallKind {
    /** A macOS `.app` bundle — whether it arrived via `.dmg` or `.zip` makes no difference to how
     * it gets replaced. */
    MAC_APP_BUNDLE,

    /** Installed via the Windows MSI (per-machine, under Program Files or a chosen directory). */
    WINDOWS_INSTALLED,

    /** A `.zip` app image extracted somewhere the user can write to. */
    WINDOWS_PORTABLE,

    /** Installed from a `.deb`/`.rpm` under a system directory (`/opt`, `/usr`) — never
     * self-replaced; an in-app update falls back to the release page. */
    LINUX_PACKAGE,

    /** A `.zip` app image extracted somewhere the user can write to. */
    LINUX_PORTABLE,

    /** Installed by the user directly (not through an app store) — the only Android form an
     * in-app update can act on. */
    ANDROID_SIDELOADED,

    /** Installed through Google Play, which already updates the app itself. */
    ANDROID_STORE,

    /** Running under `./gradlew run` or an IDE launch — no packaged app to replace. */
    DEVELOPMENT,

    /** Could not be determined (e.g. an unrecognized directory layout). */
    UNKNOWN,
}

/**
 * Where this running build lives on disk, as far as an in-app update needs to know.
 *
 * @param appRoot The directory an in-app update would replace (a macOS `.app` bundle, or a
 *   Windows/Linux portable app-image directory). `null` when there is nothing to replace
 *   ([InstallKind.DEVELOPMENT], [InstallKind.ANDROID_SIDELOADED], [InstallKind.ANDROID_STORE], or
 *   an installer-managed [InstallKind.WINDOWS_INSTALLED] whose files an update never touches
 *   directly).
 * @param launcherPath The executable this process was launched from, used to relaunch after an
 *   update. `null` when unknown.
 * @param parentWritable Whether [appRoot]'s parent directory can actually be written to — checked
 *   by probing (creating and deleting a temporary file), not by permission bits alone, since those
 *   can't be trusted on Windows. `false` for kinds with no [appRoot].
 * @param translocated Whether this process is running from macOS App Translocation's read-only,
 *   randomized copy (`.../AppTranslocation/<uuid>/d/Keryx.app`) rather than its real location — a
 *   quarantined `.app` opened somewhere other than `/Applications` ends up here, and its actual
 *   install path cannot be recovered, so self-replacement must be refused.
 */
data class InstallLocation(
    val kind: InstallKind,
    val appRoot: String?,
    val launcherPath: String?,
    val parentWritable: Boolean,
    val translocated: Boolean,
)

/**
 * Detects how this running build was installed. Desktop inspects the launcher path jpackage
 * records (`jpackage.app-path`) or, failing that, the running process's own command; Android
 * inspects the package that installed this app (the same signal [selfUpdateCheckSupported] uses).
 */
expect fun detectInstallLocation(): InstallLocation
