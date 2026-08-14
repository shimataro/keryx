package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log
import java.io.File
import java.security.MessageDigest

/** Bytes hashed per read. Keeps peak memory constant regardless of how large the snapshot is. */
private const val DIGEST_CHUNK_BYTES = 64 * 1024

actual object ContentDigest {
    /**
     * Computes the hex-encoded SHA-256 of the file at [path], reading it in fixed-size chunks.
     *
     * @param path The file to hash.
     * @return The digest as a lower-case hex string, or `null` if the file cannot be read.
     */
    actual fun sha256File(path: String): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_CHUNK_BYTES)
        return try {
            File(path).inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        } catch (e: Exception) {
            // A missing/unreadable snapshot is not fatal here: the caller simply treats it as
            // "no digest to compare" and uploads, which is the pre-existing behaviour.
            Log.warn(TAG, "Could not hash $path: ${e.message}")
            null
        }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            append(HEX[(b.toInt() shr 4) and 0xF])
            append(HEX[b.toInt() and 0xF])
        }
    }

    private const val HEX = "0123456789abcdef"
    private const val TAG = "ContentDigest"
}
