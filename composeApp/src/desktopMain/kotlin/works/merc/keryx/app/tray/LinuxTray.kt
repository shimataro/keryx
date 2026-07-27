package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.types.UInt32
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.drawUnreadDot
import java.awt.image.BufferedImage

private const val LOG_TAG = "LinuxTray"

/**
 * Creates and manages a Linux system tray entry using the D-Bus StatusNotifierItem protocol.
 *
 * Registers the tray item and menu, updates their state from the provided values, and routes
 * tray actions and new article notifications to the supplied callbacks and notification flow.
 *
 * @param connection The D-Bus connection used to export and announce the tray objects.
 */
@Composable
internal fun LinuxTray(
    connection: SniConnection,
    trayBaseImage: BufferedImage?,
    notificationIcon: BufferedImage?,
    unreadCount: Long,
    toggleLabel: String,
    quitLabel: String,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnQuit by rememberUpdatedState(onQuit)

    val item = remember(connection) {
        SniStatusNotifierItem(
            objectPath = SniConnection.ITEM_PATH,
            menuPath = SniConnection.MENU_PATH,
            onNewIcon = { connection.emit { StatusNotifierItem.NewIcon(SniConnection.ITEM_PATH) } },
            onNewToolTip = { connection.emit { StatusNotifierItem.NewToolTip(SniConnection.ITEM_PATH) } },
        )
    }
    val menu = remember(connection) {
        SniDBusMenu(
            objectPath = SniConnection.MENU_PATH,
            initialState = TrayMenuState(toggleLabel, quitLabel),
            onLayoutUpdated = { revision ->
                connection.emit {
                    DBusMenu.LayoutUpdated(SniConnection.MENU_PATH, UInt32(revision.toLong()), MENU_ROOT_ID)
                }
            },
        )
    }
    val notifier = remember(connection, notificationIcon) { LinuxNotifier(connection, notificationIcon) }

    // Keyed on the *presence* of the unread dot rather than on the count: drawUnreadDot renders
    // no digits, so every positive count produces the same glyph, and keying on the count would
    // re-encode the pixmaps and emit NewIcon on every article of a bulk refresh. The captured
    // unreadCount is only read when the boolean flips, which is exactly when it matters.
    val hasDot = hasUnreadDot(unreadCount)
    val pixmaps = remember(trayBaseImage, hasDot) {
        trayBaseImage?.let { toSniPixmaps(drawUnreadDot(it, unreadCount)) }.orEmpty()
    }

    DisposableEffect(connection, item, menu) {
        runCatching { connection.exportObjects(item, menu) }
            .onFailure { Log.warn(LOG_TAG, "Could not export the StatusNotifierItem objects", it) }
        onDispose { connection.detach() }
    }

    // Populate the item before announcing it, so the host never sees a blank entry. Effects
    // launch in declaration order, so these run ahead of the announce effect below.
    LaunchedEffect(item, pixmaps) {
        item.updateIcon(pixmaps)
    }
    LaunchedEffect(item, unreadCount) {
        item.updateToolTip(if (unreadCount > 0) "Keryx ($unreadCount)" else "Keryx")
    }
    LaunchedEffect(menu, toggleLabel, quitLabel) {
        menu.updateState(TrayMenuState(toggleLabel, quitLabel))
    }

    LaunchedEffect(connection) {
        announce(connection)
        // Fires when the watcher comes back (a plasmashell restart, say).
        connection.reregisterRequests.collect { announce(connection) }
    }

    // Host-initiated actions arrive on dbus-java worker threads; collecting them here moves
    // them onto the UI thread before they touch Compose state.
    LaunchedEffect(item, menu) {
        merge(item.activations, menu.toggleRequests).collect { currentOnToggle() }
    }
    LaunchedEffect(menu) {
        menu.quitRequests.collect { currentOnQuit() }
    }

    LaunchedEffect(notifier) {
        newArticleNotifications.collect { message ->
            withContext(Dispatchers.IO) { notifier.notify(summary = "Keryx", body = message) }
        }
    }
}

/**
 * Announces the tray objects to the StatusNotifierWatcher.
 *
 * @param connection The SNI connection used for registration.
 */
private suspend fun announce(connection: SniConnection) {
    withContext(Dispatchers.IO) {
        runCatching { connection.announce() }
            .onFailure { Log.warn(LOG_TAG, "Could not register with the StatusNotifierWatcher", it) }
    }
}
