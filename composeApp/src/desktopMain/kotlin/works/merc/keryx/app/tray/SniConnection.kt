package works.merc.keryx.app.tray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

private const val LOG_TAG = "SniConnection"

private const val WATCHER_BUS = "org.kde.StatusNotifierWatcher"
private const val WATCHER_PATH = "/StatusNotifierWatcher"
private const val NOTIFICATIONS_BUS = "org.freedesktop.Notifications"
private const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"

/**
 * An owned session-bus connection with our well-known name already acquired, ready to export
 * the StatusNotifierItem objects.
 *
 * Obtained through [tryCreate], which returns `null` (leaving no connection behind) whenever
 * the native tray isn't usable - no session bus, no StatusNotifierWatcher, name already taken.
 * Callers fall back to the AWT tray in that case.
 */
internal class SniConnection private constructor(
    private val connection: DBusConnection,
    private val busName: String,
    /** Whether a notification daemon was present when the connection was opened. */
    val notificationsAvailable: Boolean,
) {
    private val _reregisterRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emitted when the StatusNotifierWatcher reappears (a `plasmashell` restart, say).
     * Collected by `LinuxTray`, which re-registers off the signal thread - issuing a blocking
     * method call from inside a dbus-java signal handler is a known deadlock shape.
     */
    val reregisterRequests: SharedFlow<Unit> = _reregisterRequests

    /**
     * Watches for the StatusNotifierWatcher reappearing. Installed once, for the connection's own
     * lifetime, so no later call mutates it across the IO and composition threads. A failure here
     * only costs restart recovery, so it must not abort [announce].
     */
    private val nameOwnerHandler: AutoCloseable? = runCatching {
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == WATCHER_BUS && signal.newOwner.isNotEmpty()) {
                // Never call back into the bus from a signal thread: publish the request and
                // let LinuxTray re-announce from a coroutine instead.
                _reregisterRequests.tryEmit(Unit)
            }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not watch for $WATCHER_BUS restarts", it) }.getOrNull()

    private val _notificationActionInvoked = MutableSharedFlow<UInt32>(extraBufferCapacity = 4)

    /**
     * Emitted with a notification's id whenever the notification daemon reports its `"default"`
     * action was invoked (a click on the notification body). Unscoped by sender, so this fires
     * for every application's notifications, not just ours - `LinuxTray` filters by id via
     * [LinuxNotifier.consumeIfOwn] before treating it as a click on one of Keryx's own.
     */
    val notificationActionInvoked: SharedFlow<UInt32> = _notificationActionInvoked

    /**
     * Watches for notification-click events. Installed once, for the connection's own lifetime,
     * same rationale as [nameOwnerHandler]. A failure here only costs the click-to-front feature,
     * so it must not abort [announce].
     */
    private val actionInvokedHandler: AutoCloseable? = runCatching {
        connection.addSigHandler(FreedesktopNotifications.ActionInvoked::class.java) { signal ->
            if (signal.actionKey == "default") _notificationActionInvoked.tryEmit(signal.id)
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not watch for notification clicks", it) }.getOrNull()

    private val _notificationClosed = MutableSharedFlow<UInt32>(extraBufferCapacity = 4)

    /** Emitted with a notification's id whenever the daemon reports it closed, for any reason. */
    val notificationClosed: SharedFlow<UInt32> = _notificationClosed

    /**
     * Watches for notification-close events (expired, dismissed, or programmatically closed), so
     * a pending id is forgotten even when the user never clicks the notification. Same
     * installation/failure rationale as [actionInvokedHandler].
     */
    private val notificationClosedHandler: AutoCloseable? = runCatching {
        connection.addSigHandler(FreedesktopNotifications.NotificationClosed::class.java) { signal ->
            _notificationClosed.tryEmit(signal.id)
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not watch for notification close events", it) }.getOrNull()

    /**
     * Exports the status notifier item and its menu on the connection.
     *
     * @param item The status notifier item to export.
     * @param menu The D-Bus menu to export.
     */
    fun exportObjects(item: SniStatusNotifierItem, menu: SniDBusMenu) {
        connection.exportObject(ITEM_PATH, item)
        connection.exportObject(MENU_PATH, menu)
    }

    /**
     * Registers the status notifier item with the watcher.
     *
     * Safe to call again whenever [reregisterRequests] fires - the watcher treats a repeat
     * registration of the same name as a no-op.
     */
    fun announce() {
        val watcher = connection.getRemoteObject(WATCHER_BUS, WATCHER_PATH, StatusNotifierWatcher::class.java)
        watcher.RegisterStatusNotifierItem(busName)
        Log.info(LOG_TAG, "Registered $busName with the StatusNotifierWatcher")
    }

    /** Undoes [exportObjects], keeping the connection itself open. */
    fun detach() {
        runCatching { connection.unExportObject(MENU_PATH) }
            .onFailure { Log.warn(LOG_TAG, "Could not unexport the dbusmenu object", it) }
        runCatching { connection.unExportObject(ITEM_PATH) }
            .onFailure { Log.warn(LOG_TAG, "Could not unexport the StatusNotifierItem object", it) }
    }

    /**
     * Sends a D-Bus signal produced by [signal].
     *
     * @param signal Factory for the signal to send.
     */
    fun emit(signal: () -> DBusSignal) {
        runCatching { connection.sendMessage(signal()) }
            .onFailure { Log.warn(LOG_TAG, "Could not emit a D-Bus signal", it) }
    }

    /** A proxy for the notification daemon, or `null` when none was present. */
    fun notifications(): FreedesktopNotifications? {
        if (!notificationsAvailable) return null
        return runCatching {
            connection.getRemoteObject(
                NOTIFICATIONS_BUS,
                NOTIFICATIONS_PATH,
                FreedesktopNotifications::class.java,
            )
        }.onFailure { Log.warn(LOG_TAG, "Could not obtain the notification daemon proxy", it) }
            .getOrNull()
    }

    /**
     * Releases the owned bus name and disconnects from the session bus.
     */
    fun close() {
        runCatching { nameOwnerHandler?.close() }
            .onFailure { Log.warn(LOG_TAG, "Could not remove the NameOwnerChanged handler", it) }
        runCatching { actionInvokedHandler?.close() }
            .onFailure { Log.warn(LOG_TAG, "Could not remove the ActionInvoked handler", it) }
        runCatching { notificationClosedHandler?.close() }
            .onFailure { Log.warn(LOG_TAG, "Could not remove the NotificationClosed handler", it) }
        runCatching { connection.releaseBusName(busName) }
            .onFailure { Log.warn(LOG_TAG, "Could not release $busName", it) }
        runCatching { connection.disconnect() }
            .onFailure { Log.warn(LOG_TAG, "Could not disconnect from the session bus", it) }
    }

    internal companion object {
        const val ITEM_PATH = "/StatusNotifierItem"

        /**
         * KDE's watcher defaults an item's object path to `/StatusNotifierItem`, so the menu
         * has to live underneath it rather than at some arbitrary path.
         */
        const val MENU_PATH = "/StatusNotifierItem/menu"

        private val DEFAULT_TIMEOUT = 2.seconds

        /**
         * Attempts to create a session-bus connection for the native tray.
         *
         * @param timeout The maximum time allowed for connection setup.
         * @return A configured connection, or `null` when the native tray is unavailable or setup fails.
         */
        fun tryCreate(timeout: Duration = DEFAULT_TIMEOUT): SniConnection? = openDBusConnectionWithTimeout(
            logTag = LOG_TAG,
            component = "StatusNotifierItem tray",
            timeout = timeout,
            timeoutMessage = "falling back to the AWT tray",
            onLateClose = { it.close() },
            open = ::open,
        )

        /**
         * Opens and validates a dedicated session-bus connection for the status notifier.
         *
         * @return A configured connection, or `null` when the status notifier watcher is unavailable or setup fails.
         */
        private fun open(): SniConnection? {
            // withShared(false) is required: the default is a process-wide refcounted
            // connection, and we own a well-known name and export objects on this one.
            val connection = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            var handedOver = false
            try {
                val bus = connection.getRemoteObject(DBUS_BUS, DBUS_PATH, DBus::class.java)
                if (!bus.NameHasOwner(WATCHER_BUS)) {
                    Log.info(LOG_TAG, "No $WATCHER_BUS on the session bus; using the AWT tray instead")
                    return null
                }
                val busName = sniBusName(ProcessHandle.current().pid())
                connection.requestBusName(busName)

                val notificationsAvailable = bus.NameHasOwner(NOTIFICATIONS_BUS)
                if (!notificationsAvailable) {
                    Log.warn(LOG_TAG, "$NOTIFICATIONS_BUS is not available; desktop notifications will be skipped")
                }

                val created = SniConnection(connection, busName, notificationsAvailable)
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
