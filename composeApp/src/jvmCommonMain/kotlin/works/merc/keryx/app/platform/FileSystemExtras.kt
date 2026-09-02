package works.merc.keryx.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
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
        } catch (e: NoSuchFileException) {
            true // nothing there to begin with
        } catch (e: IOException) {
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
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    actual fun move(from: String, to: String): Boolean {
        val source = File(from).toPath()
        val destination = File(to).toPath()
        return try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            moveAcrossFilesystems(source, destination)
        } catch (e: IOException) {
            false
        }
    }

    /** Non-atomic fallback for [move] when [source] and [destination] are on different
     * filesystems/volumes — a plain rename (and therefore [StandardCopyOption.ATOMIC_MOVE]) cannot
     * cross that boundary, so this copies the whole tree first and only removes [source] once the
     * copy fully succeeds. */
    private fun moveAcrossFilesystems(source: Path, destination: Path): Boolean {
        return try {
            Files.walk(source).use { stream ->
                stream.sorted().forEach { path ->
                    val target = destination.resolve(source.relativize(path))
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
            deleteRecursively(source.toString())
            true
        } catch (e: IOException) {
            false
        }
    }
}
