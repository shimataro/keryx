package works.merc.keryx.app.data.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

actual object Pkce {
    private val random = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    actual fun generateVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    actual fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
        return base64Url.encodeToString(digest)
    }
}
