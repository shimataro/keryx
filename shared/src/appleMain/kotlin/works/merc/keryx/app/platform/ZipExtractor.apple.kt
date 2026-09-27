package works.merc.keryx.app.platform

/**
 * Not used on Apple: ZIP extraction only serves the self-replacing in-app updater, and Apple builds
 * update through the App Store or Sparkle instead (`updateModule` is never installed there).
 */
actual object ZipExtractor {
    actual fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>): Unit =
        throw UnsupportedOperationException("ZIP extraction is not supported on Apple platforms")

    actual fun validate(zipPath: String, destDir: String, maxBytes: Long): Unit =
        throw UnsupportedOperationException("ZIP extraction is not supported on Apple platforms")
}
