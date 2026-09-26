package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.tempFilePath
import works.merc.keryx.app.tempFileWith

internal fun uploadSourceOf(bytes: ByteArray): String = tempFileWith(bytes, "keryx-upload-")

internal fun downloadDestPath(): String = tempFilePath("keryx-download-").also { FileIO.writeBytes(it, ByteArray(0)) }

internal fun bytesAt(path: String): ByteArray = FileIO.readBytes(path) ?: error("No file at $path")
