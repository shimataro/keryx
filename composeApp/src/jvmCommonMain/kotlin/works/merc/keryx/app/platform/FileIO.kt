package works.merc.keryx.app.platform

import java.io.File

actual object FileIO {
    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun readText(path: String): String? =
        File(path).takeIf { it.exists() }?.readText()

    actual fun writeText(path: String, content: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    actual fun readBytes(path: String): ByteArray? =
        File(path).takeIf { it.exists() }?.readBytes()

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
    }

    actual fun delete(path: String) {
        File(path).delete()
    }

    actual fun join(vararg parts: String): String =
        parts.fold(File("")) { acc, p -> if (acc.path.isEmpty()) File(p) else File(acc, p) }.path
}
