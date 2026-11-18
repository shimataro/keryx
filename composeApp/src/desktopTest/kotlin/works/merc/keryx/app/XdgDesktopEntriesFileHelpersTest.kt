package works.merc.keryx.app

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The file helpers in `XdgDesktopEntries.kt` not reached through the registrar tests. */
class XdgDesktopEntriesFileHelpersTest {

    private val dir: File = Files.createTempDirectory("keryx-xdg").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `readOrNull returns null for a missing file`() {
        assertNull(readOrNull(File(dir, "missing")))
    }

    @Test
    fun `readOrNull returns null for a directory`() {
        assertNull(readOrNull(dir))
    }

    @Test
    fun `readOrNull returns the contents of a file`() {
        val file = File(dir, "a.txt").apply { writeText("hello") }

        assertEquals("hello", readOrNull(file))
    }

    @Test
    fun `writeAtomically creates missing parents and leaves no temp file`() {
        val target = File(dir, "nested/deeper/out.list")

        writeAtomically(target, "one")

        assertEquals("one", target.readText())
        assertEquals(listOf("out.list"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `writeAtomically replaces existing content`() {
        val target = File(dir, "out.list").apply { writeText("old") }

        writeAtomically(target, "new")

        assertEquals("new", target.readText())
    }

    @Test
    fun `writeIfChanged writes a missing file and reports true`() {
        val file = File(dir, "x/new.txt")

        assertTrue(writeIfChanged(file, "content"))
        assertEquals("content", file.readText())
    }

    @Test
    fun `writeIfChanged skips identical content and reports false`() {
        val file = File(dir, "same.txt").apply { writeText("same") }
        val before = file.lastModified()
        file.setLastModified(before - 10_000)
        val pinned = file.lastModified()

        assertFalse(writeIfChanged(file, "same"))
        assertEquals(pinned, file.lastModified())
    }

    @Test
    fun `writeIfChanged rewrites different content and reports true`() {
        val file = File(dir, "diff.txt").apply { writeText("old") }

        assertTrue(writeIfChanged(file, "new"))
        assertEquals("new", file.readText())
    }
}
