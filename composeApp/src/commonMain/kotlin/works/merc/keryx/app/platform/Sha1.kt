package works.merc.keryx.app.platform

/**
 * SHA-1 digest, provided per-platform because commonMain cannot reference `java.security`.
 * Used to build deterministic UUIDv5 article ids (see [works.merc.keryx.app.domain.IdGenerator.articleId]).
 * Mirrors the expect/actual crypto pattern used by `Pkce`.
 */
expect object Sha1 {
    /** SHA-1 of [input] (20 bytes). */
    fun digest(input: ByteArray): ByteArray
}
