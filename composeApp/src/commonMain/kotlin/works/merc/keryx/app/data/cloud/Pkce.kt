package works.merc.keryx.app.data.cloud

/** PKCE (RFC 7636) helpers for the OAuth authorization-code flow. */
expect object Pkce {
    /** A high-entropy, URL-safe code verifier. */
    fun generateVerifier(): String

    /** base64url(SHA-256(verifier)) — the S256 code challenge. */
    fun challengeS256(verifier: String): String
}
