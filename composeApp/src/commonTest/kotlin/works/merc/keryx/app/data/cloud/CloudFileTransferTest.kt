package works.merc.keryx.app.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The file/HTTP streaming helpers the cloud providers share. The sync DB is the largest payload
 * this app moves, so these exist specifically so it never becomes a `ByteArray`.
 */
class CloudFileTransferTest {

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("keryx-transfer-", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    /** Bigger than the helpers' internal chunk, so the multi-chunk loop is actually exercised. */
    private fun multiChunkBytes(): ByteArray = Random(7).nextBytes(200 * 1024)

    @Test
    fun bodyIsWrittenToTheDestinationFileVerbatim() = runTest {
        val payload = multiChunkBytes()
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }
        val dest = File.createTempFile("keryx-dest-", ".bin").apply { deleteOnExit() }

        client.get("https://example.invalid/x").writeBodyToFile(dest.absolutePath)

        assertContentEquals(payload, dest.readBytes())
    }

    @Test
    fun writingToAnExistingFileReplacesItRatherThanAppending() = runTest {
        // The download destination is a reused temp path, so a shorter payload must not leave the
        // tail of a previous, larger one behind — that would be a corrupt DB handed to the merge.
        val dest = tempFile(ByteArray(100 * 1024) { 0xFF.toByte() })
        val payload = byteArrayOf(1, 2, 3, 4)
        val client = HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }) { expectSuccess = false }

        client.get("https://example.invalid/x").writeBodyToFile(dest.absolutePath)

        assertContentEquals(payload, dest.readBytes())
    }

    @Test
    fun uploadContentStreamsTheFileAndReportsItsLength() = runTest {
        val payload = multiChunkBytes()
        val source = tempFile(payload)
        var sent: ByteArray? = null
        val client = HttpClient(
            MockEngine { request ->
                sent = (request.body as OutgoingContent.WriteChannelContent).collect()
                respond("", HttpStatusCode.OK)
            }
        ) { expectSuccess = false }

        val content = FileUploadContent(source.absolutePath)
        assertEquals(payload.size.toLong(), content.contentLength)
        client.post("https://example.invalid/x") { setBody(content) }

        assertContentEquals(payload, sent)
    }

    @Test
    fun uploadContentWrapsTheFileInItsPrefixAndSuffix() = runTest {
        // Google Drive's multipart/related upload needs a JSON metadata part before the file and a
        // boundary terminator after it, without the envelope forcing the file into memory.
        val payload = multiChunkBytes()
        val source = tempFile(payload)
        val prefix = "PREFIX".encodeToByteArray()
        val suffix = "SUFFIX".encodeToByteArray()
        var sent: ByteArray? = null
        val client = HttpClient(
            MockEngine { request ->
                sent = (request.body as OutgoingContent.WriteChannelContent).collect()
                respond("", HttpStatusCode.OK)
            }
        ) { expectSuccess = false }

        val content = FileUploadContent(source.absolutePath, prefix = prefix, suffix = suffix)
        assertEquals((prefix.size + payload.size + suffix.size).toLong(), content.contentLength)
        client.post("https://example.invalid/x") { setBody(content) }

        assertContentEquals(prefix + payload + suffix, sent)
    }

    /** Drains a [OutgoingContent.WriteChannelContent] into the bytes it would put on the wire. */
    private suspend fun OutgoingContent.WriteChannelContent.collect(): ByteArray {
        val channel = ByteChannel(autoFlush = true)
        val collected = mutableListOf<Byte>()
        kotlinx.coroutines.coroutineScope {
            launch {
                writeTo(channel)
                channel.close()
            }
            while (true) {
                val packet = channel.readRemaining(8 * 1024L)
                if (packet.exhausted()) break
                collected += packet.readByteArray().toList()
            }
        }
        return collected.toByteArray()
    }
}
