package works.merc.keryx.app.platform

import java.io.File

/**
 * Android implementation of [AppDirs], backed by the app-private directories the OS already
 * creates and manages — no OS-specific path conventions to replicate, unlike desktop.
 */
actual object AppDirs {
    /**
     * Resolves the application data directory.
     *
     * @return The absolute path of the application data directory (`Context.filesDir`, an
     * app-private directory the OS creates automatically and never clears on its own).
     */
    actual fun appDataDir(): String = AndroidAppContext.application.filesDir.absolutePath

    /**
     * Resolves the application cache directory.
     *
     * @return The absolute path of the application cache directory (`Context.cacheDir`, which the
     * OS may clear under storage pressure — appropriate for the favicon/thumbnail cache this
     * backs).
     */
    actual fun cacheDir(): String = AndroidAppContext.application.cacheDir.absolutePath

    /**
     * Resolves the directory for transient files (e.g. a downloaded cloud DB during merge).
     *
     * @return The absolute path of a `tmp` subdirectory under `Context.cacheDir`, kept separate
     * from [cacheDir] itself so transient files are never mistaken for cached favicons/thumbnails.
     */
    actual fun tempDir(): String {
        val dir = File(AndroidAppContext.application.cacheDir, "tmp")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }
}
