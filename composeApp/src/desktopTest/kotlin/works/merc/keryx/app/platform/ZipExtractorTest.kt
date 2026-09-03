package works.merc.keryx.app.platform

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipExtractorTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File = createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val root = newTempDir("zip-extractor-source")
        val zipFile = File(root, "archive.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return zipFile
    }

    @Test
    fun extractsFilesPreservingTheirRelativePaths() {
        val zip = zipOf("Keryx.app/Contents/MacOS/Keryx" to "binary", "Keryx.app/Contents/Info.plist" to "plist")
        val dest = newTempDir("zip-extractor-dest")

        ZipExtractor.extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())

        assertEquals("binary", File(dest, "Keryx.app/Contents/MacOS/Keryx").readText())
        assertEquals("plist", File(dest, "Keryx.app/Contents/Info.plist").readText())
    }

    // Windows' WinNTFileSystem always answers ACCESS_EXECUTE with true, so `canExecute()` cannot
    // observe this distinction there — the executable bit itself is POSIX-only.
    @Test
    fun marksOnlyTheListedEntriesAsExecutable() {
        if (isWindows) return

        val zip = zipOf("Keryx.app/Contents/MacOS/Keryx" to "binary", "Keryx.app/Contents/Info.plist" to "plist")
        val dest = newTempDir("zip-extractor-dest-exec")

        ZipExtractor.extract(
            zip.path, dest.path, maxBytes = 1_000_000,
            executableEntries = setOf("Keryx.app/Contents/MacOS/Keryx"),
        )

        assertTrue(File(dest, "Keryx.app/Contents/MacOS/Keryx").canExecute())
        // Info.plist is a data file — never made executable just because some other entry was.
        assertTrue(!File(dest, "Keryx.app/Contents/Info.plist").canExecute())
    }

    @Test
    fun rejectsAZipSlipEntry() {
        val zip = zipOf("../../etc/escaped" to "malicious")
        val dest = newTempDir("zip-extractor-dest-slip")

        assertFailsWith<IllegalStateException> {
            ZipExtractor.extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())
        }
    }

    @Test
    fun stopsOnceTheByteLimitIsExceeded() {
        val zip = zipOf("big.bin" to "x".repeat(1024))
        val dest = newTempDir("zip-extractor-dest-toolarge")

        assertFailsWith<IllegalStateException> {
            ZipExtractor.extract(zip.path, dest.path, maxBytes = 100, executableEntries = emptySet())
        }
    }

    @Test
    fun extractsDirectoryEntries() {
        val root = newTempDir("zip-extractor-source-dir")
        val zipFile = File(root, "archive.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("Keryx.app/Contents/"))
            zip.closeEntry()
        }
        val dest = newTempDir("zip-extractor-dest-dir")

        ZipExtractor.extract(zipFile.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())

        assertTrue(File(dest, "Keryx.app/Contents").isDirectory)
    }

    // --- validate: the same guards, for an extraction performed by something else (ditto) ---

    @Test
    fun validateAcceptsAWellFormedArchiveWithoutCreatingAnything() {
        val zip = zipOf("Keryx.app/Contents/MacOS/Keryx" to "binary", "Keryx.app/Contents/Info.plist" to "plist")
        val root = newTempDir("zip-extractor-validate")
        // Deliberately absent: a caller that rejects an archive must be left with nothing on disk,
        // and the destination is only ever resolved against, never written to.
        val dest = File(root, "extracted")

        ZipExtractor.validate(zip.path, dest.path, maxBytes = 1_000_000)

        assertFalse(dest.exists(), "validate must not create the destination directory")
    }

    @Test
    fun validateRejectsAZipSlipEntry() {
        val zip = zipOf("../../etc/escaped" to "malicious")
        val dest = newTempDir("zip-extractor-validate-slip")

        assertFailsWith<IllegalStateException> {
            ZipExtractor.validate(zip.path, dest.path, maxBytes = 1_000_000)
        }
    }

    @Test
    fun validateRejectsAnArchiveOverTheByteLimit() {
        val zip = zipOf("big.bin" to "x".repeat(1024))
        val dest = newTempDir("zip-extractor-validate-toolarge")

        assertFailsWith<IllegalStateException> {
            ZipExtractor.validate(zip.path, dest.path, maxBytes = 100)
        }
    }
}
