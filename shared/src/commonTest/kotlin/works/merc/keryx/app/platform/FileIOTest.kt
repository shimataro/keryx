package works.merc.keryx.app.platform

import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileIOTest {

    private fun tempPath(vararg parts: String): String =
        FileIO.join(SystemTemporaryDirectory.toString(), "fileio-test-${Random.nextLong().toULong()}", *parts)

    @Test
    fun textRoundTripsAndCreatesParentDirectories() {
        val path = tempPath("nested", "settings.json")

        FileIO.writeText(path, "{\"themeMode\":\"dark\"} — 日本語")

        assertTrue(FileIO.exists(path))
        assertEquals("{\"themeMode\":\"dark\"} — 日本語", FileIO.readText(path))
        FileIO.delete(path)
        assertFalse(FileIO.exists(path))
    }

    @Test
    fun bytesRoundTrip() {
        val path = tempPath("blob.bin")
        val bytes = ByteArray(256) { it.toByte() }

        FileIO.writeBytes(path, bytes)

        assertContentEquals(bytes, FileIO.readBytes(path))
        FileIO.delete(path)
    }

    @Test
    fun overwriteReplacesTheWholeFile() {
        val path = tempPath("overwrite.txt")

        FileIO.writeText(path, "a much longer first version")
        FileIO.writeText(path, "short")

        assertEquals("short", FileIO.readText(path))
        FileIO.delete(path)
    }

    @Test
    fun missingFileReadsAsNullAndDeletesQuietly() {
        val path = tempPath("missing.txt")

        assertNull(FileIO.readText(path))
        assertNull(FileIO.readBytes(path))
        FileIO.delete(path)
    }

    @Test
    fun joinUsesThePlatformSeparator() {
        val joined = FileIO.join("base", "child", "leaf.db")

        assertTrue(joined.endsWith("leaf.db"))
        assertEquals(3, joined.split('/', '\\').size)
    }
}
