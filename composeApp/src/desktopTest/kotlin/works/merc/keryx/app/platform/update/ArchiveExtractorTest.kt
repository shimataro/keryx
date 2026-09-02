package works.merc.keryx.app.platform.update

import works.merc.keryx.app.platform.isMacOs
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers what [DittoArchiveExtractor] can be held to without a real signed bundle: that the guards
 * `ditto` has none of its own run **before** it is ever launched — platform-independent, since
 * [works.merc.keryx.app.platform.ZipExtractor.validate] throws before any process starts — and, on
 * macOS only, that the round trip really does restore symbolic links, which is the entire reason
 * this extractor exists at all.
 */
class ArchiveExtractorTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File = createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun zipOf(root: File, vararg entries: Pair<String, String>): File {
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
    fun dittoIsNeverLaunchedForAZipSlipEntry() {
        val root = newTempDir("archive-extractor-slip")
        val zip = zipOf(root, "../../etc/escaped" to "malicious")
        val dest = File(root, "out")

        assertFailsWith<IllegalStateException> {
            DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())
        }

        // If validate ran first, nothing was created at all — ditto creates its destination itself.
        assertFalse(dest.exists(), "a rejected archive must leave nothing on disk")
    }

    @Test
    fun dittoIsNeverLaunchedForAnArchiveOverTheByteLimit() {
        val root = newTempDir("archive-extractor-toolarge")
        val zip = zipOf(root, "big.bin" to "x".repeat(1024))
        val dest = File(root, "out")

        assertFailsWith<IllegalStateException> {
            DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 100, executableEntries = emptySet())
        }

        assertFalse(dest.exists(), "a rejected archive must leave nothing on disk")
    }

    @Test
    fun defaultArchiveExtractorUsesDittoOnlyOnMacOs() {
        assertIs<DittoArchiveExtractor>(
            defaultArchiveExtractor(isMac = true),
            "a macOS bundle's sealed symlinks can only survive an out-of-process extraction",
        )
        assertSame(InProcessArchiveExtractor, defaultArchiveExtractor(isMac = false))
    }

    // Same macOS gate as the round-trip test below, and the case validate structurally cannot
    // cover: it can't even tell which entries are symlinks, let alone where they point.
    @Test
    fun anExtractedSymlinkPointingOutsideTheDestinationIsRejected() {
        if (!isMacOs) return

        val root = newTempDir("archive-extractor-escape")
        val outside = File(root, "outside").apply { mkdirs() }
        val source = File(root, "src").apply { mkdirs() }
        // ditto creates the link itself and exits 0 (it only refuses to write *through* one), so
        // nothing before the result walk catches this.
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        val zip = File(root, "archive.zip")
        val zipExit = ProcessBuilder("/usr/bin/zip", "-qry", zip.path, "src").directory(root).start().waitFor()
        assertEquals(0, zipExit, "fixture archive could not be built")
        val dest = File(root, "out")

        val failure = assertFailsWith<IllegalStateException> {
            DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())
        }

        assertTrue(
            failure.message.orEmpty().contains("outside"),
            "the failure must name the escaping link: ${failure.message}",
        )
    }

    // The case a lexical `..` check gets wrong: `a` -> `.` is genuinely contained, and `b` -> `a/..`
    // normalizes textually to the destination itself while really pointing at its parent, because
    // normalize() collapses the `..` against the link rather than against what it points at.
    @Test
    fun anExtractedSymlinkThatEscapesThroughAnotherSymlinkIsRejected() {
        if (!isMacOs) return

        val root = newTempDir("archive-extractor-chain")
        val source = File(root, "src").apply { mkdirs() }
        Files.createSymbolicLink(File(source, "a").toPath(), Path.of("."))
        Files.createSymbolicLink(File(source, "b").toPath(), Path.of("a/.."))
        val zip = File(root, "archive.zip")
        // Zipped from *inside* src, so both links land at the extraction root. One level deeper and
        // `a/..` would resolve to the destination itself, which is legitimately contained.
        val zipExit = ProcessBuilder("/usr/bin/zip", "-qry", zip.path, "a", "b").directory(source).start().waitFor()
        assertEquals(0, zipExit, "fixture archive could not be built")
        val dest = File(root, "out")

        val failure = assertFailsWith<IllegalStateException> {
            DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())
        }

        assertTrue(
            failure.message.orEmpty().contains("outside"),
            "the chained escape must be caught, not just the direct one: ${failure.message}",
        )
    }

    // Every one of a jpackage bundle's 43 legal-notices links is dangling: jlink emits
    // legal/<module>/LICENSE -> ../java.base/LICENSE without shipping legal/java.base at all. A
    // resolver that requires the target to exist would therefore reject every real release, which
    // is why the containment check canonicalizes rather than calling toRealPath.
    @Test
    fun anExtractedDanglingSymlinkThatStaysInsideIsAccepted() {
        if (!isMacOs) return

        val root = newTempDir("archive-extractor-dangling")
        val source = File(root, "src").apply { mkdirs() }
        val moduleDir = File(source, "legal/java.security.jgss").apply { mkdirs() }
        Files.createSymbolicLink(File(moduleDir, "LICENSE").toPath(), Path.of("../java.base/LICENSE"))
        val zip = File(root, "archive.zip")
        val zipExit = ProcessBuilder("/usr/bin/zip", "-qry", zip.path, "legal").directory(source).start().waitFor()
        assertEquals(0, zipExit, "fixture archive could not be built")
        val dest = File(root, "out")

        DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())

        val link = File(dest, "legal/java.security.jgss/LICENSE").toPath()
        assertTrue(Files.isSymbolicLink(link), "the link must survive as a link")
        assertFalse(Files.exists(link), "and it is expected to dangle, exactly as jlink emits it")
    }

    // `ditto` is macOS-only, so this is the one test that cannot run on the Linux/Windows runners —
    // and the only one that exercises the behavior the whole seam exists for.
    @Test
    fun dittoRestoresAStoredSymbolicLinkAsALink() {
        if (!isMacOs) return

        val root = newTempDir("archive-extractor-symlink")
        val source = File(root, "src").apply { mkdirs() }
        File(source, "real.txt").writeText("real")
        Files.createSymbolicLink(File(source, "link.txt").toPath(), Path.of("real.txt"))
        val zip = File(root, "archive.zip")
        // `zip -y` (store links as links), exactly how release.yml builds the macOS asset — and the
        // half of the fix that java.util.zip cannot read back.
        val zipExit = ProcessBuilder("/usr/bin/zip", "-qry", zip.path, "src").directory(root).start().waitFor()
        assertEquals(0, zipExit, "fixture archive could not be built")
        val dest = File(root, "out")

        DittoArchiveExtractor().extract(zip.path, dest.path, maxBytes = 1_000_000, executableEntries = emptySet())

        assertEquals("real", File(dest, "src/real.txt").readText())
        val link = File(dest, "src/link.txt").toPath()
        assertTrue(Files.isSymbolicLink(link), "a stored symlink must come back as a link, not a file holding its target")
        assertEquals("real.txt", Files.readSymbolicLink(link).toString())
    }
}
