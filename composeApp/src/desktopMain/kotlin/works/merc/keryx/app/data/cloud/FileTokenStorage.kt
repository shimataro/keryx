package works.merc.keryx.app.data.cloud

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.AppDirs
import java.io.File

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
            file.writeText(json.encodeToString(tokens))
        }.onFailure { e -> Log.warn(TAG, "Token file could not be written", e) }
        runCatching { file.setReadable(false, false); file.setReadable(true, true) }
        runCatching { file.setWritable(false, false); file.setWritable(true, true) }
    }

    override fun load(): OAuthTokens? =
        file.takeIf { it.exists() }
            ?.let {
                runCatching { json.decodeFromString<OAuthTokens>(it.readText()) }
                    .onFailure { e -> Log.warn(TAG, "Token file could not be read/decoded", e) }
                    .getOrNull()
            }

    override fun clear() {
        runCatching { file.delete() }
            .onFailure { e -> Log.warn(TAG, "Token file delete failed", e) }
    }

    private companion object {
        const val TAG = "TokenStorage"
    }
}
