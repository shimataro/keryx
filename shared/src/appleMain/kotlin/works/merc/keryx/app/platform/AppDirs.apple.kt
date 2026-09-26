package works.merc.keryx.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import works.merc.keryx.app.core.APP_NAME

/**
 * Apple app directories: `Application Support/Keryx` and `Caches/Keryx` in the user domain. For a
 * sandboxed app these resolve inside its container; unsandboxed on macOS they are the very same
 * `~/Library/...` paths the Compose desktop build uses (see "Apple Native Apps (SwiftUI)" in
 * `docs/app-architecture.md` for when the two may share data).
 */
actual object AppDirs {
    /**
     * When set (by `KeryxSdk.start`'s `dataDirectory`), every app directory lives under it instead —
     * for previews, tests and tooling that must never open the user's real data.
     */
    internal var rootOverride: String? = null

    actual fun appDataDir(): String = rootOverride?.let { ensureDir(it) } ?: userDirectory(NSApplicationSupportDirectory)

    actual fun cacheDir(): String = rootOverride?.let { ensureDir(FileIO.join(it, "Caches")) } ?: userDirectory(NSCachesDirectory)

    actual fun tempDir(): String = NSTemporaryDirectory().trimEnd('/')

    @OptIn(ExperimentalForeignApi::class)
    private fun userDirectory(directory: NSSearchPathDirectory): String {
        val base = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String
        return ensureDir(FileIO.join(base, APP_NAME))
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun ensureDir(dir: String): String {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }
}
