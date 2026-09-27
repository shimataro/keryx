package works.merc.keryx.app.platform

import java.security.MessageDigest

actual object Sha1 {
    actual fun digest(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(input)
}
