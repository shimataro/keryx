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
 * Opens a session-bus connection via [open] on a background thread, waiting up to [timeout] before
 * giving up — this runs before the window is shown (from `tray/SniConnection` and
 * `appmenu/AppMenuConnection`), so an unresponsive session bus must not stop Keryx from starting. A
 * connection that lands after the deadline is closed via [onLateClose] rather than leaked.
 * [component] names what's being set up, for the shared log wording; [timeoutMessage] is the tail of
 * the timeout-specific log line (what happens instead, e.g. "falling back to the AWT tray").
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
