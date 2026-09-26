package works.merc.keryx.app.core

/**
 * Build-time application metadata surfaced into common code (About screen, etc.).
 *
 * `expect` because the values come from the platform-generated `BuildConfig`,
 * which lives in the platform source set — mirroring [CloudStorageAvailability].
 */
expect object AppInfo {
    /** Human-readable app version, e.g. "1.0.0". */
    val version: String

    /** GitHub "owner/repo" slug the update checker polls, e.g. "shimataro/keryx". */
    val updateRepo: String
}
