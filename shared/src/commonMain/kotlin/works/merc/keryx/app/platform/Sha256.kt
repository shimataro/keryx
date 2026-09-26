package works.merc.keryx.app.platform

/**
 * SHA-256 digest of an in-memory buffer, provided per-platform because commonMain has no crypto.
 * Used for the PKCE S256 code challenge. (Hashing a whole file is [ContentDigest]'s job.)
 */
expect object Sha256 {
    /** SHA-256 of [input] (32 bytes). */
    fun digest(input: ByteArray): ByteArray
}
