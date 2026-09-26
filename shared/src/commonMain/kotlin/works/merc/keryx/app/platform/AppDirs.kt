package works.merc.keryx.app.platform

/**
 * Platform-specific application directories.
 *
 * On desktop these resolve to the OS-conventional application-support and
 * cache locations (e.g. `~/Library/Application Support/Keryx` on macOS).
 */
expect object AppDirs {
    /** Directory for the local DB and `local_settings.json`. Created if absent. */
    fun appDataDir(): String

    /** Directory for cached images (favicons / thumbnails). Created if absent. */
    fun cacheDir(): String

    /** Directory for transient files (e.g. the downloaded cloud DB during merge). */
    fun tempDir(): String
}
