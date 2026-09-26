package works.merc.keryx.app.data.cloud

import java.io.File

/**
 * Temp-file plumbing for the file-path based [CloudStorage] API.
 *
 * Uploads stream from a path and downloads stream to one, so the provider tests need real files
 * rather than byte arrays. Everything lands in the JVM's temp directory and is marked
 * `deleteOnExit`, which is enough for a test process.
 */
internal fun uploadSourceOf(bytes: ByteArray): String =
    File.createTempFile("keryx-upload-", ".bin").apply {
        deleteOnExit()
        writeBytes(bytes)
    }.absolutePath

/** A path for a download destination. The file is created (and emptied) so the path is writable. */
internal fun downloadDestPath(): String =
    File.createTempFile("keryx-download-", ".bin").apply { deleteOnExit() }.absolutePath

/** The bytes a download wrote to [path]. */
internal fun bytesAt(path: String): ByteArray = File(path).readBytes()
