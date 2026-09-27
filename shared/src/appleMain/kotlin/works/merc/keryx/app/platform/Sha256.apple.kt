package works.merc.keryx.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

actual object Sha256 {
    @OptIn(ExperimentalForeignApi::class)
    actual fun digest(input: ByteArray): ByteArray {
        val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { o ->
            if (input.isEmpty()) {
                CC_SHA256(null, 0u, o.addressOf(0))
            } else {
                input.usePinned { i -> CC_SHA256(i.addressOf(0), input.size.convert(), o.addressOf(0)) }
            }
        }
        return out.asByteArray()
    }
}
