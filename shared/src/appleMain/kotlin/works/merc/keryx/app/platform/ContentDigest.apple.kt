package works.merc.keryx.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import works.merc.keryx.app.core.Log

/** Bytes hashed per read. Keeps peak memory constant regardless of how large the snapshot is. */
private const val DIGEST_CHUNK_BYTES = 64 * 1024

actual object ContentDigest {
    /** Hex SHA-256 of the file at [path], streamed in fixed-size chunks; `null` if unreadable. */
    @OptIn(ExperimentalForeignApi::class)
    actual fun sha256File(path: String): String? = try {
        memScoped {
            val ctx = alloc<CC_SHA256_CTX>()
            CC_SHA256_Init(ctx.ptr)
            val buffer = ByteArray(DIGEST_CHUNK_BYTES)
            SystemFileSystem.source(Path(path)).buffered().use { source ->
                while (true) {
                    val read = source.readAtMostTo(buffer, 0, buffer.size)
                    if (read <= 0) break
                    buffer.usePinned { CC_SHA256_Update(ctx.ptr, it.addressOf(0), read.convert()) }
                }
            }
            val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
            out.usePinned { CC_SHA256_Final(it.addressOf(0), ctx.ptr) }
            out.joinToString("") { it.toString(16).padStart(2, '0') }
        }
    } catch (e: Exception) {
        // A missing/unreadable snapshot is not fatal here: the caller simply treats it as
        // "no digest to compare" and uploads, which is the pre-existing behaviour.
        Log.warn(TAG, "Could not hash $path: ${e.message}")
        null
    }

    private const val TAG = "ContentDigest"
}
