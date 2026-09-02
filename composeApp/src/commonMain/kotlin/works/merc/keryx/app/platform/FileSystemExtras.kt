package works.merc.keryx.app.platform

/**
 * Filesystem operations neither `kotlinx-io`'s `SystemFileSystem` (no recursive delete, no free-space
 * query) nor [FileIO] (deliberately kept to minimal settings/DB file access) cover. `jvmCommonMain`
 * implements this once via `java.nio.file`, shared by desktop and Android exactly like [FileIO] and
 * `Gzip` already are.
 */
expect object FileSystemExtras {
    /**
     * Deletes the file or directory tree at [path]. A symbolic link is deleted as the link itself,
     * never followed into — a macOS `.app` bundle's `Contents/runtime` legal-notices directory is
     * full of real symlinks, and following one instead of removing it outright would delete files
     * outside [path] entirely (or, for a link that escapes the tree, files that were never meant
     * to be touched at all).
     *
     * @return `true` if [path] no longer exists afterward — including when it never did, since a
     *   caller sweeping stale files only cares that the end state is "gone".
     */
    fun deleteRecursively(path: String): Boolean

    /**
     * Free space, in bytes, on the filesystem containing [path].
     *
     * @return The free space, or `0` if it can't be determined (e.g. [path]'s directory doesn't
     *   exist yet). Callers that need this before creating [path] should query an existing
     *   ancestor directory instead of [path] itself.
     */
    fun usableSpaceBytes(path: String): Long
}
