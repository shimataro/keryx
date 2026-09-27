package works.merc.keryx.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.zlib.MAX_WBITS
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** Bytes processed per read/write. Keeps peak memory constant regardless of the file's size. */
private const val GZIP_CHUNK_BYTES = 64 * 1024

/** windowBits + 16 selects a gzip (not raw zlib) wrapper for deflate; + 32 auto-detects either for inflate. */
private const val GZIP_WRAPPER = 16
private const val AUTO_DETECT_WRAPPER = 32

/** Apple [Gzip] over the system zlib — the same `.gz` format the JVM's GZIP streams read and write. */
@OptIn(ExperimentalForeignApi::class)
actual object Gzip {
    actual fun compressFile(sourcePath: String, destPath: String) {
        SystemFileSystem.source(Path(sourcePath)).buffered().use { input ->
            SystemFileSystem.sink(Path(destPath)).use { output ->
                memScoped {
                    val stream = alloc<z_stream>()
                    stream.zalloc = null
                    stream.zfree = null
                    stream.opaque = null
                    val init = deflateInit2_(
                        stream.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, MAX_WBITS + GZIP_WRAPPER, 8, Z_DEFAULT_STRATEGY,
                        ZLIB_VERSION, sizeOf<z_stream>().convert(),
                    )
                    if (init != Z_OK) throw IOException("deflateInit2 failed ($init)")
                    try {
                        val inBuf = ByteArray(GZIP_CHUNK_BYTES)
                        val outBuf = ByteArray(GZIP_CHUNK_BYTES)
                        var finished = false
                        while (!finished) {
                            val read = input.readAtMostTo(inBuf, 0, inBuf.size)
                            val flush = if (read <= 0) Z_FINISH else Z_NO_FLUSH
                            inBuf.usePinned { inPin ->
                                stream.next_in = inPin.addressOf(0).reinterpret()
                                stream.avail_in = (if (read > 0) read else 0).convert()
                                do {
                                    val produced = outBuf.usePinned { outPin ->
                                        stream.next_out = outPin.addressOf(0).reinterpret()
                                        stream.avail_out = outBuf.size.convert()
                                        val rc = deflate(stream.ptr, flush)
                                        if (rc != Z_OK && rc != Z_STREAM_END && rc != Z_BUF_ERROR) throw IOException("deflate failed ($rc)")
                                        if (rc == Z_STREAM_END) finished = true
                                        outBuf.size - stream.avail_out.toInt()
                                    }
                                    output.writeBytes(outBuf, produced)
                                } while (stream.avail_out.toInt() == 0)
                            }
                        }
                    } finally {
                        deflateEnd(stream.ptr)
                    }
                }
            }
        }
    }

    /**
     * Inflates [sourcePath] into [destPath], refusing (with an [IOException]) once the output would
     * exceed [maxBytes] — a decompression-bomb guard — or when the input is corrupt or truncated.
     */
    actual fun decompressFile(sourcePath: String, destPath: String, maxBytes: Long) {
        SystemFileSystem.source(Path(sourcePath)).buffered().use { input ->
            SystemFileSystem.sink(Path(destPath)).use { output ->
                inflateInto(input, output, maxBytes)
            }
        }
    }

    private fun inflateInto(input: Source, output: RawSink, maxBytes: Long) = memScoped {
        val stream = alloc<z_stream>()
        stream.zalloc = null
        stream.zfree = null
        stream.opaque = null
        stream.next_in = null
        stream.avail_in = 0u
        val init = inflateInit2_(stream.ptr, MAX_WBITS + AUTO_DETECT_WRAPPER, ZLIB_VERSION, sizeOf<z_stream>().convert())
        if (init != Z_OK) throw IOException("inflateInit2 failed ($init)")
        try {
            val inBuf = ByteArray(GZIP_CHUNK_BYTES)
            val outBuf = ByteArray(GZIP_CHUNK_BYTES)
            var total = 0L
            var ended = false
            while (!ended) {
                val read = input.readAtMostTo(inBuf, 0, inBuf.size)
                if (read <= 0) throw IOException("Unexpected end of gzip input")
                inBuf.usePinned { inPin ->
                    stream.next_in = inPin.addressOf(0).reinterpret()
                    stream.avail_in = read.convert()
                    do {
                        val produced = outBuf.usePinned { outPin ->
                            stream.next_out = outPin.addressOf(0).reinterpret()
                            stream.avail_out = outBuf.size.convert()
                            val rc = inflate(stream.ptr, Z_NO_FLUSH)
                            when (rc) {
                                Z_STREAM_END -> ended = true
                                Z_OK, Z_BUF_ERROR -> Unit
                                else -> throw IOException("Corrupt gzip data ($rc)")
                            }
                            outBuf.size - stream.avail_out.toInt()
                        }
                        total += produced
                        if (total > maxBytes) throw IOException("Decompressed output exceeds the $maxBytes-byte limit")
                        output.writeBytes(outBuf, produced)
                    } while (!ended && stream.avail_out.toInt() == 0)
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }

    private fun RawSink.writeBytes(bytes: ByteArray, count: Int) {
        if (count <= 0) return
        val buffer = kotlinx.io.Buffer()
        buffer.write(bytes, 0, count)
        write(buffer, count.toLong())
    }
}
