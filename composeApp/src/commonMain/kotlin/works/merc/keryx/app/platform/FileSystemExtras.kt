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

    /**
     * Marks the file at [path] as executable (POSIX permission bits). A no-op that returns `true`
     * on a platform with no such concept — Windows determines executability from the file
     * extension (`.exe`) instead, which a ZIP extraction never needs to set.
     */
    fun setExecutable(path: String): Boolean

    /**
     * Whether [path] (an existing directory) can actually be written to — determined by creating
     * and deleting a temporary file inside it, not by permission bits alone: `java.nio.file`'s own
     * `Files.isWritable` does not reliably reflect Windows ACLs.
     */
    fun isDirectoryWritable(path: String): Boolean

    /**
     * Moves (renames) [from] to [to]. Tries an atomic rename first; when [from] and [to] are on
     * different filesystems or volumes (`EXDEV`, which a plain rename cannot bridge), falls back to
     * a non-atomic copy-then-delete.
     *
     * @return `true` if [to] exists and [from] no longer does afterward.
     */
    fun move(from: String, to: String): Boolean
}
