package works.merc.keryx.app.platform

/**
 * Extracts a ZIP archive (an in-app update's self-replace asset) to a destination directory.
 * `jvmCommonMain` implements this once via `java.util.zip`, shared by desktop and Android exactly
 * like [FileIO]/`Gzip`/[FileSystemExtras] already are — though only the desktop actual of
 * `UpdateInstaller` currently calls it (self-replace has no Android equivalent).
 */
expect object ZipExtractor {
    /**
     * @param zipPath The `.zip` file to extract — already downloaded and digest-verified.
     * @param destDir The directory to extract into. Created if it doesn't exist.
     * @param maxBytes Upper bound on the total uncompressed size written. A defense against a
     *   maliciously (or corruptly) high compression-ratio archive exhausting disk before this
     *   finishes — the same concern [works.merc.keryx.app.data.cloud.CloudFileTransfer]'s own
     *   `maxBytes` guards against for a downloaded file, applied here to what's *inside* one.
     * @param executableEntries Archive-root-relative entry paths (e.g.
     *   `"Keryx.app/Contents/MacOS/Keryx"`) to mark executable after extraction —
     *   `java.util.zip` exposes no Unix permission bits from the archive itself, so the caller
     *   must say which entries need them (see `UpdateScriptWriter`'s own list, verified against a
     *   real built bundle).
     * @throws IllegalStateException on a "zip slip" entry (one whose resolved path would land
     *   outside [destDir]), an entry-count overflow, or exceeding [maxBytes].
     */
    fun extract(zipPath: String, destDir: String, maxBytes: Long, executableEntries: Set<String>)
}
