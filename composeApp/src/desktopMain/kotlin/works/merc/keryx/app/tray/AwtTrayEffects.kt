package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.SharedFlow
import works.merc.keryx.app.core.APP_NAME
import java.awt.TrayIcon

/**
 * The label-bearing surface [MacTrayMenu] and [WindowsTrayMenu] share, so [AwtTrayMenuTextEffects]
 * can push the current texts onto either without knowing which widget toolkit backs it.
 */
internal interface AwtTrayMenuLabels {
    /**
     * Pushes the current labels onto the already-built widgets.
     *
     * @param toggle The label for the show/hide entry.
     * @param quit The label for the quit entry.
     */
    fun setLabels(toggle: String, quit: String)

    /** Pushes the in-app update entry's label and enabled state onto the already-built widget. */
    fun setUpdateEntry(entry: TrayUpdateEntry)
}

/**
 * Keeps a raw `TrayIcon`'s tooltip and its menu's labels in step with the given values — the
 * effects [MacTray] and [WindowsTray] both run, in this order, in place of Compose's `Tray()`.
 *
 * @param trayIcon The tray icon whose tooltip is updated.
 * @param menu The tray menu whose labels and update entry are updated.
 * @param tooltip The tray icon tooltip.
 * @param toggleLabel The toggle item's label.
 * @param quitLabel The quit item's label.
 * @param updateEntry The in-app update menu entry.
 */
@Composable
internal fun AwtTrayMenuTextEffects(
    trayIcon: TrayIcon,
    menu: AwtTrayMenuLabels,
    tooltip: String,
    toggleLabel: String,
    quitLabel: String,
    updateEntry: TrayUpdateEntry,
) {
    LaunchedEffect(trayIcon, tooltip) {
        trayIcon.toolTip = tooltip
    }
    LaunchedEffect(menu, toggleLabel, quitLabel) {
        menu.setLabels(toggle = toggleLabel, quit = quitLabel)
    }
    LaunchedEffect(menu, updateEntry) {
        menu.setUpdateEntry(updateEntry)
    }
}

/**
 * Shows each of [newArticleNotifications] as a balloon/notification on [trayIcon].
 *
 * Tray()'s own composable body is what turns a queued TrayState notification into an actual
 * TrayIcon.displayMessage(...) call; since [MacTray] and [WindowsTray] replace Tray() entirely on
 * their platforms, they must consume newArticleNotifications themselves — exactly as LinuxTray does.
 */
@Composable
internal fun AwtTrayNotificationsEffect(trayIcon: TrayIcon, newArticleNotifications: SharedFlow<String>) {
    LaunchedEffect(trayIcon) {
        newArticleNotifications.collect { message ->
            trayIcon.displayMessage(APP_NAME, message, TrayIcon.MessageType.NONE)
        }
    }
}
