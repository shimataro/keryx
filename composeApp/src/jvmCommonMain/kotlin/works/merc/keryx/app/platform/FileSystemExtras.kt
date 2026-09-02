package works.merc.keryx.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

actual object FileSystemExtras {
    actual fun deleteRecursively(path: String): Boolean {
        val root = File(path).toPath()
        return try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file) // deletes a symlink itself, never its target
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // A broken symlink's target can't be stat'd, but its own directory entry
                        // still needs removing.
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        } catch (_: NoSuchFileException) {
            true // nothing there to begin with
        } catch (_: IOException) {
            !File(path).exists()
        }
    }

    actual fun usableSpaceBytes(path: String): Long {
        val existingAncestor = generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() }
        return existingAncestor?.usableSpace ?: 0L
    }

    actual fun setExecutable(path: String): Boolean = File(path).setExecutable(true)

    actual fun isDirectoryWritable(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory) return false
        return try {
            val probe = File.createTempFile("keryx-writable-probe", ".tmp", dir)
            probe.delete()
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    actual fun move(from: String, to: String): Boolean {
        val source = File(from).toPath()
        val destination = File(to).toPath()
        return try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            moveAcrossFilesystems(source, destination)
        } catch (_: IOException) {
            false
        }
    }

    /** Non-atomic fallback for [move] when [source] and [destination] are on different
     * filesystems/volumes — a plain rename (and therefore [StandardCopyOption.ATOMIC_MOVE]) cannot
     * cross that boundary, so this copies the whole tree first and only removes [source] once the
     * copy fully succeeds. */
    private fun moveAcrossFilesystems(source: Path, destination: Path): Boolean {
        return try {
            copyTree(source, destination)
            deleteRecursively(source.toString())
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Copies the tree at [source] to [destination], reproducing a symbolic link **as a link** — not
     * as a copy of whatever it points at. Split out of [moveAcrossFilesystems] (rather than left
     * inline) purely so it is testable: a cross-volume move cannot be provoked from a unit test.
     *
     * The two [LinkOption.NOFOLLOW_LINKS] arguments are the whole point. Both `Files.copy` and
     * `Files.isDirectory` follow links by default, which silently turned each of the 43 symlinks in
     * a macOS `.app`'s bundled JDK (`Contents/runtime/.../legal/`) into a regular file — and
     * `CodeResources` seals those *as links*, so the staged bundle then failed the in-app updater's
     * own `codesign --verify --strict --deep` check on any install whose cache and install
     * directory happen to sit on different volumes. `Files.walk` needs no such flag: it already
     * does not descend into a symlinked directory.
     */
    internal fun copyTree(source: Path, destination: Path) {
        Files.walk(source).use { stream ->
            stream.sorted().forEach { path ->
                val target = destination.resolve(source.relativize(path))
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                }
            }
        }
    }
}
