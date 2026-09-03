package works.merc.keryx.app.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemExtrasTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File =
        createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun deleteRecursivelyRemovesAFileTree() {
        val root = newTempDir("file-system-extras-tree")
        File(root, "sub").mkdirs()
        File(root, "sub/leaf.txt").writeText("x")
        File(root, "top.txt").writeText("y")

        assertTrue(FileSystemExtras.deleteRecursively(root.path))

        assertFalse(root.exists())
    }

    @Test
    fun deleteRecursivelyOnAMissingPathIsAlreadyGone() {
        val root = newTempDir("file-system-extras-missing")
        val missing = File(root, "does-not-exist")

        assertTrue(FileSystemExtras.deleteRecursively(missing.path))
    }

    @Test
    fun deleteRecursivelyRemovesASymlinkWithoutTouchingItsTarget() {
        val root = newTempDir("file-system-extras-symlink")
        val targetDir = File(root, "target").apply { mkdirs() }
        val targetFile = File(targetDir, "keep-me.txt").apply { writeText("keep") }
        val linkedTree = File(root, "linked").apply { mkdirs() }
        val link = File(linkedTree, "link-to-target")
        Files.createSymbolicLink(link.toPath(), targetDir.toPath())

        assertTrue(FileSystemExtras.deleteRecursively(linkedTree.path))

        assertFalse(linkedTree.exists())
        assertTrue(targetFile.exists(), "the symlink's target must survive deleting the link itself")
    }

    @Test
    fun deleteRecursivelyOnASingleFileRemovesJustThatFile() {
        val root = newTempDir("file-system-extras-single-file")
        val file = File(root, "solo.txt").apply { writeText("x") }

        assertTrue(FileSystemExtras.deleteRecursively(file.path))

        assertFalse(file.exists())
        assertTrue(root.exists())
    }

    @Test
    fun usableSpaceBytesIsPositiveForAnExistingWritableDirectory() {
        val root = newTempDir("file-system-extras-space")

        assertTrue(FileSystemExtras.usableSpaceBytes(root.path) > 0)
    }

    @Test
    fun usableSpaceBytesFallsBackToAnExistingAncestor() {
        val root = newTempDir("file-system-extras-space-ancestor")
        val notYetCreated = File(root, "does/not/exist/yet")

        assertTrue(FileSystemExtras.usableSpaceBytes(notYetCreated.path) > 0)
    }

    // Windows' WinNTFileSystem always answers ACCESS_EXECUTE with true, so `canExecute()` cannot
    // distinguish "made executable" from "was already". The executable bit itself is POSIX-only.
    @Test
    fun setExecutableMarksTheFileAsExecutable() {
        if (isWindows) return

        val root = newTempDir("file-system-extras-executable")
        val file = File(root, "script.sh").apply { writeText("#!/bin/sh") }
        assertFalse(file.canExecute())

        assertTrue(FileSystemExtras.setExecutable(file.path))

        assertTrue(file.canExecute())
    }

    @Test
    fun isDirectoryWritableIsTrueForAWritableDirectory() {
        val root = newTempDir("file-system-extras-writable")

        assertTrue(FileSystemExtras.isDirectoryWritable(root.path))
    }

    @Test
    fun isDirectoryWritableIsFalseForANonDirectory() {
        val root = newTempDir("file-system-extras-not-a-dir")
        val file = File(root, "not-a-directory").apply { writeText("x") }

        assertFalse(FileSystemExtras.isDirectoryWritable(file.path))
    }

    // Windows ignores File.setWritable(false) on directories (FILE_ATTRIBUTE_READONLY has no
    // effect there), so this negative case needs a real ACL to exercise on Windows — out of scope
    // here. The positive case above still runs on every platform.
    @Test
    fun isDirectoryWritableIsFalseWhenWriteAccessIsRevoked() {
        if (isWindows) return

        val root = newTempDir("file-system-extras-unwritable")
        val dir = File(root, "locked").apply { mkdirs() }
        check(dir.setWritable(false)) { "test setup: could not revoke write permission" }

        try {
            assertFalse(FileSystemExtras.isDirectoryWritable(dir.path))
        } finally {
            dir.setWritable(true) // allow tearDown()'s deleteRecursively to succeed
        }
    }

    @Test
    fun moveRenamesAFileWithinTheSameDirectory() {
        val root = newTempDir("file-system-extras-move")
        val source = File(root, "source.txt").apply { writeText("payload") }
        val destination = File(root, "destination.txt")

        assertTrue(FileSystemExtras.move(source.path, destination.path))

        assertFalse(source.exists())
        assertEquals("payload", destination.readText())
    }

    @Test
    fun moveRenamesADirectoryTreeWithinTheSameDirectory() {
        val root = newTempDir("file-system-extras-move-tree")
        val source = File(root, "source").apply { mkdirs() }
        File(source, "nested").mkdirs()
        File(source, "nested/leaf.txt").writeText("payload")
        val destination = File(root, "destination")

        assertTrue(FileSystemExtras.move(source.path, destination.path))

        assertFalse(source.exists())
        assertEquals("payload", File(destination, "nested/leaf.txt").readText())
    }

    @Test
    fun moveOfAMissingSourceFails() {
        val root = newTempDir("file-system-extras-move-missing")
        val source = File(root, "does-not-exist")
        val destination = File(root, "destination.txt")

        assertFalse(FileSystemExtras.move(source.path, destination.path))
    }

    // --- copyTree: the cross-volume staging copy (see its own KDoc for why symlinks matter) ---

    // Creating a symlink on Windows needs a privilege a test run cannot assume, and the behavior
    // being pinned here is a POSIX app bundle's own links.
    @Test
    fun copyTreeReproducesASymlinkAsALinkRatherThanACopy() {
        if (isWindows) return

        val root = newTempDir("file-system-extras-copy-symlink")
        val source = File(root, "source").apply { mkdirs() }
        File(source, "real.txt").writeText("real")
        // COPY_ATTRIBUTES only guarantees last-modified-time; whether it carries POSIX permissions
        // is platform-dependent, and a staged bundle whose launcher lost its executable bit fails
        // the swap script's health check and silently rolls back. Pin it here.
        val launcher = File(source, "launcher").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        Files.createSymbolicLink(File(source, "link.txt").toPath(), Path.of("real.txt"))
        val destination = File(root, "destination")

        FileSystemExtras.copyTree(source.toPath(), destination.toPath())

        assertEquals("real", File(destination, "real.txt").readText())
        assertTrue(launcher.canExecute() && File(destination, "launcher").canExecute(), "an executable file must stay executable")
        val copied = File(destination, "link.txt").toPath()
        assertTrue(
            Files.isSymbolicLink(copied),
            "a symlink must survive the copy as a link — a macOS bundle's CodeResources seals it as one",
        )
        assertEquals("real.txt", Files.readSymbolicLink(copied).toString())
    }

    @Test
    fun copyTreeDoesNotTurnADirectorySymlinkIntoARealDirectory() {
        if (isWindows) return

        val root = newTempDir("file-system-extras-copy-dir-symlink")
        val source = File(root, "source").apply { mkdirs() }
        File(source, "target").mkdirs()
        File(source, "target/inside.txt").writeText("inside")
        Files.createSymbolicLink(File(source, "link-dir").toPath(), Path.of("target"))
        val destination = File(root, "destination")

        FileSystemExtras.copyTree(source.toPath(), destination.toPath())

        val copied = File(destination, "link-dir").toPath()
        assertTrue(Files.isSymbolicLink(copied), "a directory symlink must not become a real directory")
        assertEquals("target", Files.readSymbolicLink(copied).toString())
    }
}
