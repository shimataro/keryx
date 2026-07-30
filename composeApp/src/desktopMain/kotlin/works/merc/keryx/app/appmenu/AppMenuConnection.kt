package works.merc.keryx.app.appmenu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import works.merc.keryx.app.DBUS_BUS
import works.merc.keryx.app.DBUS_PATH
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.openDBusConnectionWithTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val LOG_TAG = "AppMenuConnection"

private const val REGISTRAR_BUS = "com.canonical.AppMenu.Registrar"
private const val REGISTRAR_PATH = "/com/canonical/AppMenu/Registrar"

/**
 * A dedicated session-bus connection for the KDE Global Menu, mirroring `tray/SniConnection` in
 * shape: it exports the [AppMenuDBusMenu] object and registers this app's window with the
 * `com.canonical.AppMenu.Registrar` service.
 *
 * Obtained through [tryCreate], which returns `null` (leaving no connection behind) whenever the
 * registrar isn't present — no session bus, or no `com.canonical.AppMenu.Registrar` owner (i.e.
 * every non-KDE environment). Callers then keep the in-window menu bar exactly as before.
 *
 * Unlike `SniConnection` this claims **no** well-known bus name: the registrar infers the calling
 * service from the D-Bus sender of `RegisterWindow`, and it — not the app — writes the
 * `_KDE_NET_WM_APPMENU_*` X11 properties.
 */
internal class AppMenuConnection private constructor(
    private val connection: DBusConnection,
) {
    private val _reregisterRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emitted when the registrar service reappears on the bus (a `plasmashell`/kded restart).
     * Collected by `AppMenuBarHost`, which re-registers off the signal thread — a blocking method
     * call from inside a dbus-java signal handler is a known deadlock shape.
     */
    val reregisterRequests: SharedFlow<Unit> = _reregisterRequests

    private val nameOwnerHandler: AutoCloseable? = runCatching {
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == REGISTRAR_BUS && signal.newOwner.isNotEmpty()) {
                // Never call back into the bus from a signal thread: publish the request and let
                // AppMenuBarHost re-register from a coroutine instead.
                _reregisterRequests.tryEmit(Unit)
            }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not watch for $REGISTRAR_BUS restarts", it) }.getOrNull()

    /** Exports the menu object on the connection at [MENU_PATH]. */
    fun exportObject(menu: AppMenuDBusMenu) {
        connection.exportObject(MENU_PATH, menu)
    }

    /** Unexports the menu object, keeping the connection open. */
    fun detach() {
        runCatching { connection.unExportObject(MENU_PATH) }
            .onFailure { Log.warn(LOG_TAG, "Could not unexport the dbusmenu object", it) }
    }

    /**
     * Registers [xid] with the registrar, pointing it at the exported menu at [MENU_PATH].
     *
     * Safe to call again whenever [reregisterRequests] fires — the registrar treats a repeat
     * registration of the same window as an update.
     *
     * @return `true` on success, `false` if the call failed (leave the in-window bar visible).
     */
    fun registerWindow(xid: Long): Boolean = runCatching {
        registrar().RegisterWindow(UInt32(xid), DBusPath(MENU_PATH))
        Log.info(LOG_TAG, "Registered window $xid with the AppMenu registrar")
        true
    }.onFailure { Log.warn(LOG_TAG, "Could not register window $xid with the AppMenu registrar", it) }
        .getOrDefault(false)

    /** Unregisters [xid] from the registrar. Best-effort. */
    fun unregisterWindow(xid: Long) {
        runCatching { registrar().UnregisterWindow(UInt32(xid)) }
            .onFailure { Log.warn(LOG_TAG, "Could not unregister window $xid from the AppMenu registrar", it) }
    }

    /** Sends a D-Bus signal produced by [signal] (the sole outbound chokepoint, like SNI). */
    fun emit(signal: () -> DBusSignal) {
        runCatching { connection.sendMessage(signal()) }
            .onFailure { Log.warn(LOG_TAG, "Could not emit a D-Bus signal", it) }
    }

    /**
     * Unregisters [xid] (if resolved), unexports the menu, and disconnects. No bus name to release
     * (none was claimed).
     */
    fun close(xid: Long?) {
        runCatching { nameOwnerHandler?.close() }
            .onFailure { Log.warn(LOG_TAG, "Could not remove the NameOwnerChanged handler", it) }
        if (xid != null) unregisterWindow(xid)
        detach()
        runCatching { connection.disconnect() }
            .onFailure { Log.warn(LOG_TAG, "Could not disconnect from the session bus", it) }
    }

    private fun registrar(): AppMenuRegistrar =
        connection.getRemoteObject(REGISTRAR_BUS, REGISTRAR_PATH, AppMenuRegistrar::class.java)

    internal companion object {
        /** Where the exported dbusmenu object lives (arbitrary, but stable). */
        const val MENU_PATH = "/com/canonical/menu/keryx"

        private val DEFAULT_TIMEOUT = 2.seconds

        /**
         * Opens a session-bus connection if a `com.canonical.AppMenu.Registrar` owner is present,
         * or returns `null` otherwise (every non-KDE environment).
         *
         * Bounded by [timeout] because this runs before the window is shown: an unresponsive
         * session bus must not stop Keryx from starting. A connection that lands after the deadline
         * is closed rather than leaked (matching `SniConnection.tryCreate`).
         */
        fun tryCreate(timeout: Duration = DEFAULT_TIMEOUT): AppMenuConnection? = openDBusConnectionWithTimeout(
            logTag = LOG_TAG,
            component = "AppMenu connection",
            timeout = timeout,
            timeoutMessage = "skipping the Global Menu",
            onLateClose = { it.close(null) },
            open = ::open,
        )

        private fun open(): AppMenuConnection? {
            // withShared(false): we export an object on this one, so it must not be the process-wide
            // refcounted connection.
            val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            var handedOver = false
            try {
                val bus = connection.getRemoteObject(DBUS_BUS, DBUS_PATH, DBus::class.java)
                if (!bus.NameHasOwner(REGISTRAR_BUS)) {
                    Log.info(LOG_TAG, "No $REGISTRAR_BUS on the session bus; skipping the Global Menu")
                    return null
                }
                val created = AppMenuConnection(connection)
                handedOver = true
                return created
            } finally {
                if (!handedOver) {
                    runCatching { connection.disconnect() }
                }
            }
        }
    }
}
