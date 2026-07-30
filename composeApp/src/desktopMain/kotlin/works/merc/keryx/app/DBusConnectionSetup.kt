package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/** The standard D-Bus bus object, used to query name ownership (`NameHasOwner`) before relying on a service. */
internal const val DBUS_BUS = "org.freedesktop.DBus"
internal const val DBUS_PATH = "/org/freedesktop/DBus"

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
    val pending = CompletableFuture.supplyAsync {
        runCatching { open() }
            .onFailure { Log.warn(logTag, "Could not set up the $component", it) }
            .getOrNull()
    }
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
