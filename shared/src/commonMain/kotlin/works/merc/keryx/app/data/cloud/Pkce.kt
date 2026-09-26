package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.platform.Sha256
import works.merc.keryx.app.platform.secureRandomBytes
import kotlin.io.encoding.Base64

/** PKCE (RFC 7636) helpers for the OAuth authorization-code flow. */
object Pkce {
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** A high-entropy, URL-safe code verifier. */
    fun generateVerifier(): String = base64Url.encode(secureRandomBytes(64))

    /** base64url(SHA-256(verifier)) — the S256 code challenge. */
    fun challengeS256(verifier: String): String = base64Url.encode(Sha256.digest(verifier.encodeToByteArray()))
}
