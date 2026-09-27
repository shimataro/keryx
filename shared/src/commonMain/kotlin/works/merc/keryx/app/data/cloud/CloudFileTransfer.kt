package works.merc.keryx.app.data.cloud

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Bytes moved per read when streaming between a file and an HTTP body. The whole point of these
 * helpers is that peak memory is a small multiple of this rather than the size of the sync DB.
 */
private const val TRANSFER_CHUNK_BYTES = 64 * 1024

/**
 * Streams this response's body into the file at [destPath], replacing any existing file.
 *
 * The sync DB is the largest thing this app moves — tens of megabytes on a mature subscription
 * list — and it always ends up on disk anyway (the merge attaches it as a file). Reading it into
 * a `ByteArray` first would put that whole payload on the heap for no benefit, so it is copied
 * through in [TRANSFER_CHUNK_BYTES] pieces instead.
 *
 * @param destPath Filesystem path to write the body to.
 * @param maxBytes Upper bound on the written size. Throws before writing a chunk that would
 * cross it — a defense against an oversized (or incompressible-archive) cloud file exhausting
 * disk before any content validation runs.
 */
internal suspend fun HttpResponse.writeBodyToFile(destPath: String, maxBytes: Long) {
    val channel = bodyAsChannel()
    var total = 0L
    SystemFileSystem.sink(Path(destPath)).buffered().use { sink ->
        while (true) {
            val packet = channel.readRemaining(TRANSFER_CHUNK_BYTES.toLong())
            if (packet.exhausted()) break
            val bytes = packet.readByteArray()
            total += bytes.size
            if (total > maxBytes) {
                error("Downloaded body exceeds the $maxBytes-byte limit")
            }
            sink.write(bytes)
        }
    }
}

/**
 * A request body that streams the file at [sourcePath] instead of holding it in memory.
 *
 * [prefix] and [suffix] are written verbatim around the file's contents, which is what lets Google
 * Drive's `multipart/related` upload (a JSON metadata part, then the file, then the boundary
 * terminator) stream as well — assembling that envelope with `prefix + bytes + suffix` would put
 * two full copies of the database on the heap.
 *
 * `contentLength` is reported from the file's own metadata plus the envelope: the providers'
 * upload endpoints are happier with a known length than with chunked transfer, and it costs one
 * stat call.
 */
internal class FileUploadContent(
    private val sourcePath: String,
    override val contentType: ContentType = ContentType.Application.OctetStream,
    private val prefix: ByteArray = ByteArray(0),
    private val suffix: ByteArray = ByteArray(0),
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long? =
        SystemFileSystem.metadataOrNull(Path(sourcePath))?.size
            ?.let { it + prefix.size + suffix.size }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        if (prefix.isNotEmpty()) channel.writeFully(prefix)
        SystemFileSystem.source(Path(sourcePath)).buffered().use { source ->
            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            while (true) {
                val read = source.readAtMostTo(buffer, 0, buffer.size)
                if (read <= 0) break
                channel.writeFully(buffer, 0, read)
            }
        }
        if (suffix.isNotEmpty()) channel.writeFully(suffix)
    }
}

/** Size in bytes of the file at [path], or 0 when it does not exist. */
internal fun fileSizeOrZero(path: String): Long =
    SystemFileSystem.metadataOrNull(Path(path))?.size ?: 0L
