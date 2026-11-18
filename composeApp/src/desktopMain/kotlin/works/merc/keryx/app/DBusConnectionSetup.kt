package works.merc.keryx.app

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import works.merc.keryx.app.core.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The standard D-Bus bus object, used to query name ownership (`NameHasOwner`) before relying on a service. */
internal const val DBUS_BUS = "org.freedesktop.DBus"
internal const val DBUS_PATH = "/org/freedesktop/DBus"

/** How long [openDBusConnectionWithTimeout]'s callers wait for the session bus by default. */
internal val DEFAULT_DBUS_SETUP_TIMEOUT: Duration = 2.seconds

/**
 * Dedicated executor for [openDBusConnectionWithTimeout]'s blocking connection setup, so a hung
 * session bus (an `open()` that never returns) ties up a thread of its own rather than a worker
 * from the shared `ForkJoinPool.commonPool()` used elsewhere in the JVM.
 */
private val dbusSetupExecutor: ExecutorService =
    Executors.newCachedThreadPool { r -> Thread(r, "dbus-setup").apply { isDaemon = true } }

/**
 * Attempts to open a D-Bus connection within the specified timeout.
 *
 * @param timeoutMessage Message describing the fallback action when the timeout expires.
 * @param onLateClose Handles a connection that completes after the timeout.
 * @param open Creates the connection.
 * @return The opened connection, or `null` if setup fails or exceeds the timeout.
 */
internal fun <T> openDBusConnectionWithTimeout(
    logTag: String,
    component: String,
    timeout: Duration,
    timeoutMessage: String,
    onLateClose: (T) -> Unit,
    open: () -> T?,
): T? {
    val pending = CompletableFuture.supplyAsync({
        runCatching { open() }
            .onFailure { Log.warn(logTag, "Could not set up the $component", it) }
            .getOrNull()
    }, dbusSetupExecutor)
    return try {
        pending.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        Log.warn(logTag, "Session bus did not answer within $timeout; $timeoutMessage")
        pending.thenAccept { late -> late?.let(onLateClose) }
        null
    } catch (e: Exception) {
        Log.warn(logTag, "Could not set up the $component", e)
        null
    }
}

/**
 * Opens a dedicated (unshared) session-bus connection, provided [requiredBusName] currently has
 * an owner, and hands it to [create].
 *
 * `withShared(false)` is required: the default is a process-wide refcounted connection, and every
 * caller exports objects on (and may own a well-known name on) the one it gets here.
 *
 * The connection is disconnected again unless [create] returns normally — i.e. when the required
 * name has no owner (logging [absentMessage] at info level under [logTag] and returning `null`) or
 * when [create] throws.
 *
 * @param create Builds the caller's wrapper from the connection and its `org.freedesktop.DBus` proxy.
 */
internal fun <T> openUnsharedSessionBus(
    requiredBusName: String,
    logTag: String,
    absentMessage: String,
    create: (connection: DBusConnection, bus: DBus) -> T,
): T? {
    val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
    var handedOver = false
    try {
        val bus = connection.getRemoteObject(DBUS_BUS, DBUS_PATH, DBus::class.java)
        if (!bus.NameHasOwner(requiredBusName)) {
            Log.info(logTag, absentMessage)
            return null
        }
        val created = create(connection, bus)
        handedOver = true
        return created
    } finally {
        if (!handedOver) {
            runCatching { connection.disconnect() }
        }
    }
}
