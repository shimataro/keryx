package works.merc.keryx.app.platform

import java.security.MessageDigest

actual object ContentDigest {
    /**
     * Computes the hex-encoded SHA-256 of [bytes].
     *
     * @param bytes The content to hash.
     * @return The digest as a lower-case hex string.
     */
    actual fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(HEX[(b.toInt() shr 4) and 0xF])
                append(HEX[b.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}
