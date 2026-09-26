package works.merc.keryx.app.platform

import java.security.MessageDigest

actual object Sha256 {
    actual fun digest(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
