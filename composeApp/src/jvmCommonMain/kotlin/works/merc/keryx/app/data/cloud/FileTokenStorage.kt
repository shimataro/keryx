package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.AppDirs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Fallback token storage used when no OS secret store is available (e.g. a
 * headless Linux box with no Secret Service). Writes JSON to a 0600 file whose
 * name is per-provider ([fileName], e.g. `.dropbox_tokens.json` /
 * `.google_drive_tokens.json`).
 */
class FileTokenStorage(
    dirOverride: String? = null,
    private val fileName: String = ".${CloudStorageType.DROPBOX.id}_tokens.json",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {

    private val file = File(dirOverride ?: AppDirs.appDataDir(), fileName)

    override fun save(tokens: OAuthTokens) {
        // Persisting must never throw: this is the last-resort store, and a failure here (unwritable
        // data dir, a pre-existing root-owned/read-only token file) would otherwise propagate up
        // through CloudSession.saveTokens() and abort the connect flow *after* the token is already
        // held in memory — leaving the user unable to link at all. Swallow and log instead; the
        // in-memory session still works, only cross-restart persistence is lost.
        runCatching {
            file.parentFile?.mkdirs()
            // Write to a sibling temp file, then atomically replace the target. writeText()
            // straight into the token file would truncate it first, so a crash or failed write
            // mid-way left a corrupt file that load() rejects, forcing the user to re-authorize.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                // Restrict the file to owner-only *before* writing the refresh token into it. Creating
                // it empty first and tightening permissions up front closes the brief window in which a
                // freshly-written token file was group/world-readable (umask-dependent): writeText into
                // an already-existing file preserves its permissions rather than recreating it.
                if (!tmp.exists()) tmp.createNewFile()
                if (!restrictToOwnerOnly(tmp)) {
                    throw IOException("Failed to restrict token file to owner-only permissions: $tmp")
                }
                tmp.writeText(json.encodeToString(tokens))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }.onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Token file could not be written", e) }
    }

    /**
     * On a POSIX filesystem, denies read/write to non-owners (defense against a permissive
     * umask) and confirms the owner itself retains access. `java.io.File`'s boolean permission
     * API cannot express "deny to non-owner" on a non-POSIX filesystem (Windows/NTFS) —
     * `setReadable(false, false)`/`setWritable(false, false)` are unsupported there and always
     * return false — so on that path this only confirms the owner retains access; the
     * owner-only guarantee itself then comes from Windows' own per-user ACL inheritance on
     * %APPDATA%/%LOCALAPPDATA%.
     */
    private fun restrictToOwnerOnly(target: File): Boolean {
        val isPosix = try {
            Files.getPosixFilePermissions(target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
        return if (isPosix) {
            target.setReadable(false, false) && target.setReadable(true, true) &&
                target.setWritable(false, false) && target.setWritable(true, true)
        } else {
            target.setReadable(true, true) && target.setWritable(true, true)
        }
    }

    override fun load(): OAuthTokens? =
        file.takeIf { it.exists() }
            ?.let {
                runCatching { json.decodeFromString<OAuthTokens>(it.readText()) }
                    .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Token file could not be read/decoded", e) }
                    .getOrNull()
            }

    override fun clear() {
        runCatching {
            // File.delete() returns false rather than throwing when it fails, which runCatching
            // alone would not observe — a lingering token file would then be reported as cleared.
            if (file.exists() && !file.delete()) {
                Log.warn(TOKEN_STORAGE_LOG_TAG, "Token file delete returned false")
            }
        }.onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Token file delete failed", e) }
    }
}
