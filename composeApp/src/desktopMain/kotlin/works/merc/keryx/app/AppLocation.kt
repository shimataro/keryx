package works.merc.keryx.app

/**
 * macOS App Translocation / Gatekeeper path randomization detection.
 *
 * When an unsigned/quarantined `.app` is launched directly from a DMG or the
 * Downloads folder, macOS runs it from a randomized, read-only path under
 * `.../AppTranslocation/...`. In that state the `keryx://` custom-URI scheme
 * cannot be resolved back to the running instance, so Dropbox OAuth linking
 * silently times out. Detecting it lets us warn the user to move the app into
 * `/Applications` (which clears quarantine and stops translocation).
 */
internal fun isTranslocatedPath(path: String?): Boolean =
    path?.contains("/AppTranslocation/") == true

/** Best-effort absolute path of the currently running executable (the app launcher). */
internal fun currentExecutablePath(): String? =
    ProcessHandle.current().info().command().orElse(null)
