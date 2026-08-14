package works.merc.keryx.app.platform

import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bytes copied per read. Keeps peak memory constant regardless of the file's size. */
private const val GZIP_CHUNK_BYTES = 64 * 1024

actual object Gzip {
    actual fun compressFile(sourcePath: String, destPath: String) {
        val buffer = ByteArray(GZIP_CHUNK_BYTES)
        File(sourcePath).inputStream().use { input ->
            GZIPOutputStream(File(destPath).outputStream()).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    actual fun decompressFile(sourcePath: String, destPath: String, maxBytes: Long) {
        val buffer = ByteArray(GZIP_CHUNK_BYTES)
        var total = 0L
        GZIPInputStream(File(sourcePath).inputStream()).use { input ->
            File(destPath).outputStream().use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IOException("Decompressed output exceeds the $maxBytes-byte limit")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }
}
