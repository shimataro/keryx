package works.merc.keryx.app

import kotlinx.io.files.SystemTemporaryDirectory
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random

/**
 * A fresh, not-yet-existing path under the system temp directory — the multiplatform stand-in for
 * `File.createTempFile(...).delete()` in tests that run on the Apple targets too.
 */
fun tempFilePath(prefix: String, suffix: String = ".bin"): String =
    FileIO.join(SystemTemporaryDirectory.toString(), "$prefix${Random.nextLong().toULong()}$suffix")

/** A temp file holding [bytes], at a fresh path (see [tempFilePath]). */
fun tempFileWith(bytes: ByteArray, prefix: String, suffix: String = ".bin"): String =
    tempFilePath(prefix, suffix).also { FileIO.writeBytes(it, bytes) }
