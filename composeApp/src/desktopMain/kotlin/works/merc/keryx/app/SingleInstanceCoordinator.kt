package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Coordinates single-instance behavior across process launches: a file lock
 * (`keryx.lock`) prevents more than one instance from running, and a loopback
 * socket lets a second (losing) launch signal the first instance to activate
 * its window instead of just exiting silently.
 */
internal class SingleInstanceCoordinator(private val appDataDir: File) {
    private var lockChannel: FileChannel? = null
    private var serverSocket: ServerSocket? = null
    private var listenerThread: Thread? = null

    /** Returns false if another instance already holds the lock. */
    fun tryAcquireLock(): Boolean {
        val file = File(appDataDir, "keryx.lock")
        val channel = RandomAccessFile(file, "rw").channel
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        }
        if (lock == null) return false
        lockChannel = channel // Held for the process lifetime so it isn't GC'd/released.
        return true
    }

    /**
     * Starts a background listener on a loopback socket and writes its port to
     * appDataDir/keryx.port (temp file + atomic rename, so a concurrent reader never
     * sees a torn write). onActivate is invoked once per incoming signal, on a
     * dedicated daemon thread (not the caller's thread). No-ops (catches and gives up)
     * if the socket can't be bound - single-instance activation is a nice-to-have,
     * not something that should crash startup.
     */
    fun startActivationListener(onActivate: (uri: String?) -> Unit) {
        val socket = try {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        } catch (e: IOException) {
            Log.warn(TAG, "Could not bind activation socket; single-instance activation disabled", e)
            return
        }
        serverSocket = socket

        try {
            val portFile = File(appDataDir, "keryx.port")
            val tmpFile = File(appDataDir, "keryx.port.tmp")
            tmpFile.writeText(socket.localPort.toString())
            Files.move(
                tmpFile.toPath(),
                portFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            Log.warn(TAG, "Could not publish activation port file; single-instance activation disabled", e)
            socket.close()
            serverSocket = null
            return
        }

        val thread = Thread {
            while (true) {
                try {
                    socket.accept().use { client ->
                        val uri = client.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readLine() }?.takeIf { it.isNotBlank() }
                        onActivate(uri)
                    }
                } catch (e: IOException) {
                    // Socket closed (via close()) - stop the loop.
                    break
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        listenerThread = thread
    }

    /** Returns false if no running instance could be reached (stale/missing port file, connection refused). */
    fun signalRunningInstance(uri: String? = null): Boolean {
        val portFile = File(appDataDir, "keryx.port")
        if (!portFile.exists()) return false
        val port = try {
            portFile.readText().trim().toInt()
        } catch (e: NumberFormatException) {
            return false
        }
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
                if (!uri.isNullOrBlank()) {
                    socket.getOutputStream().writer(Charsets.UTF_8).use { it.write(uri) }
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    private companion object {
        const val TAG = "SingleInstance"
    }

    /** Stops the listener thread and releases the socket (tests only - main() never calls this). */
    fun close() {
        serverSocket?.close()
        serverSocket = null
        listenerThread?.join(1_000)
        listenerThread = null
    }
}
