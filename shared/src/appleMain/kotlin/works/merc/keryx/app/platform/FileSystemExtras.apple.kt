package works.merc.keryx.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.posix.EXDEV
import platform.posix.S_IXUSR
import platform.posix.chmod
import platform.posix.errno
import platform.posix.lstat
import platform.posix.rename
import platform.posix.stat
import kotlin.random.Random

@OptIn(ExperimentalForeignApi::class)
actual object FileSystemExtras {
    /** Removes [path] and everything under it, deleting a symlink itself rather than its target. */
    actual fun deleteRecursively(path: String): Boolean {
        if (!entryExists(path)) return true // nothing there to begin with
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        return !entryExists(path)
    }

    actual fun usableSpaceBytes(path: String): Long {
        val existing = generateSequence(path) { p -> p.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } }
            .firstOrNull { NSFileManager.defaultManager.fileExistsAtPath(it) } ?: return 0L
        val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(existing, error = null) ?: return 0L
        return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0L
    }

    /** Adds the owner-execute bit, like the JVM's `File.setExecutable(true)`. */
    actual fun setExecutable(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return false
        chmod(path, (st.st_mode.toInt() or S_IXUSR).convert()) == 0
    }

    actual fun isDirectoryWritable(path: String): Boolean {
        if (!isDirectory(path)) return false
        val probe = FileIO.join(path, ".keryx-writable-probe-${Random.nextLong().toULong()}")
        return try {
            FileIO.writeBytes(probe, ByteArray(0))
            true
        } catch (_: Exception) {
            false
        } finally {
            FileIO.delete(probe)
        }
    }

    /**
     * An atomic rename when both paths are on one volume; across volumes, a copy (symlinks
     * reproduced as links, as `NSFileManager` does) followed by removing [from].
     */
    actual fun move(from: String, to: String): Boolean {
        if (rename(from, to) == 0) return true
        if (errno != EXDEV) return false
        val manager = NSFileManager.defaultManager
        if (!manager.copyItemAtPath(from, toPath = to, error = null)) return false
        deleteRecursively(from)
        return true
    }

    private fun entryExists(path: String): Boolean = memScoped { lstat(path, alloc<stat>().ptr) == 0 }

    private fun isDirectory(path: String): Boolean = memScoped {
        val flag = alloc<kotlinx.cinterop.BooleanVar>()
        NSFileManager.defaultManager.fileExistsAtPath(path, isDirectory = flag.ptr) && flag.value
    }
}
