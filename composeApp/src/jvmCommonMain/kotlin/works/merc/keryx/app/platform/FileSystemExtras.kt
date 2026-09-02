package works.merc.keryx.app.platform

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
}
