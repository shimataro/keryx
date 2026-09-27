package works.merc.keryx.app.platform

import works.merc.keryx.app.core.APP_NAME
import java.io.File

/**
 * Desktop implementation of [AppDirs], following each OS's conventions:
 * - macOS:   ~/Library/Application Support/Keryx, ~/Library/Caches/Keryx
 * - Windows: %APPDATA%\Keryx, %LOCALAPPDATA%\Keryx\Cache
 * - Linux:   $XDG_DATA_HOME/Keryx (~/.local/share/Keryx), $XDG_CACHE_HOME/Keryx
 */
actual object AppDirs {
    private val home = System.getProperty("user.home")

    /**
     * Resolves the platform-specific application data directory.
     *
     * @return The absolute path of the application data directory.
     */
    actual fun appDataDir(): String = ensure(
        when {
            isMacOs -> File(home, "Library/Application Support/$APP_NAME")
            isWindows -> File(env("APPDATA") ?: "$home\\AppData\\Roaming", APP_NAME)
            else -> File(env("XDG_DATA_HOME") ?: "$home/.local/share", APP_NAME)
        },
    )

    /**
     * Resolves the application cache directory for the current desktop platform.
     *
     * @return The absolute path of the application cache directory.
     */
    actual fun cacheDir(): String = ensure(
        when {
            isMacOs -> File(home, "Library/Caches/$APP_NAME")
            isWindows -> File(env("LOCALAPPDATA") ?: "$home\\AppData\\Local", "$APP_NAME\\Cache")
            else -> File(env("XDG_CACHE_HOME") ?: "$home/.cache", APP_NAME)
        },
    )

    actual fun tempDir(): String = ensure(
        File(System.getProperty("java.io.tmpdir"), APP_NAME),
    )

    private fun ensure(dir: File): String {
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}
