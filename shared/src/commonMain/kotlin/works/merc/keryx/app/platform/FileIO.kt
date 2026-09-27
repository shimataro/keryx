package works.merc.keryx.app.platform

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/** Minimal cross-platform filesystem access for settings/DB files. */
object FileIO {
    fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

    fun readText(path: String): String? =
        Path(path).takeIf { SystemFileSystem.exists(it) }?.let { p ->
            SystemFileSystem.source(p).buffered().use { it.readString() }
        }

    fun writeText(path: String, content: String) {
        val p = Path(path).also(::createParentDirectories)
        SystemFileSystem.sink(p).buffered().use { it.writeString(content) }
    }

    fun readBytes(path: String): ByteArray? =
        Path(path).takeIf { SystemFileSystem.exists(it) }?.let { p ->
            SystemFileSystem.source(p).buffered().use { it.readByteArray() }
        }

    fun writeBytes(path: String, bytes: ByteArray) {
        val p = Path(path).also(::createParentDirectories)
        SystemFileSystem.sink(p).buffered().use { it.write(bytes) }
    }

    fun delete(path: String) {
        SystemFileSystem.delete(Path(path), mustExist = false)
    }

    fun join(vararg parts: String): String =
        if (parts.isEmpty()) "" else Path(parts.first(), *parts.drop(1).toTypedArray()).toString()

    private fun createParentDirectories(path: Path) {
        path.parent?.let { SystemFileSystem.createDirectories(it) }
    }
}
