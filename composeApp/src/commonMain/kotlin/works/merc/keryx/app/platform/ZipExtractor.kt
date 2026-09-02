package works.merc.keryx.app.platform

/**
 * Extracts a ZIP archive (an in-app update's self-replace asset) to a destination directory, or
 * checks one without extracting it. `jvmCommonMain` implements this once via `java.util.zip`,
 * shared by desktop and Android exactly like [FileIO]/`Gzip`/[FileSystemExtras] already are —
 * though only the desktop actual of `UpdateInstaller` currently uses either function (self-replace
 * has no Android equivalent).
 *
 * `java.util.zip` cannot see a ZIP entry's Unix mode bits, so it cannot tell a stored symbolic link
 * apart from a regular file and writes every link out as a file containing the link's target path.
 * That makes it unusable for a **signed** macOS `.app`, whose `CodeResources` seals those links *as
 * links* — which is why the desktop installer extracts a macOS bundle with `ditto` instead and uses
 * [validate] alone to keep this object's guards in force. See `DesktopUpdateInstaller`'s
 * `ArchiveExtractor`.
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

    /**
     * Applies exactly the checks [extract] applies, without writing anything — not even [destDir]
     * itself. For an extraction performed by something other than [extract] (the desktop
     * installer's `ditto` hand-off for a macOS bundle, which has no limits of its own), this is
     * what keeps those guards in force.
     *
     * Cannot check a stored symbolic link's *target*: `java.util.zip` can't identify a symlink
     * entry in the first place (see this object's own KDoc). What covers that on macOS is `ditto`
     * itself — it normalizes a `..` entry name into the destination rather than escaping it, and
     * refuses to write *through* a symlink at all, exiting non-zero. The code-signature check that
     * follows is **not** a second line of defense here: it only inspects the bundle directory, so
     * it detects modification *inside* the bundle but not an entry placed beside it.
     *
     * @param zipPath The `.zip` file to check — already downloaded and digest-verified.
     * @param destDir The directory the caller is about to extract into. Only used to resolve entry
     *   paths against; never created or written to.
     * @param maxBytes Upper bound on the archive's total uncompressed size, as in [extract].
     * @throws IllegalStateException on the same three conditions [extract] throws for.
     */
    fun validate(zipPath: String, destDir: String, maxBytes: Long)
}
