package works.merc.keryx.app.platform

import java.io.File
import java.nio.file.Files
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

    @Test
    fun setExecutableMarksTheFileAsExecutable() {
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

    @Test
    fun isDirectoryWritableIsFalseWhenWriteAccessIsRevoked() {
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
}
