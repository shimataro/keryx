package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.drawUnreadDot
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.isWindows
import works.merc.keryx.app.rememberDrawableImage
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.tray_hide
import works.merc.keryx.app.resources.tray_icon
import works.merc.keryx.app.resources.tray_icon_outlined
import works.merc.keryx.app.resources.tray_quit
import works.merc.keryx.app.resources.tray_show
import works.merc.keryx.app.resources.tray_update_download
import works.merc.keryx.app.resources.tray_update_downloading
import works.merc.keryx.app.resources.tray_update_failed
import works.merc.keryx.app.resources.tray_update_restart
import java.awt.image.BufferedImage

/**
 * The application's system-tray icon, dispatching to the per-platform implementation, and
 * configuring its icon, actions, unread badge, and notifications.
 *
 * - macOS uses [MacTray] (a raw AWT `TrayIcon`, so a left click toggles the window instead of
 *   opening the menu).
 * - Linux uses [LinuxTray] whenever [sniConnection] is non-null, because AWT's X11 tray cannot
 *   draw a transparent icon (see the KDoc there).
 * - Windows uses [WindowsTray], because the `java.awt.PopupMenu` behind Compose's `Tray()` is
 *   drawn by a JDK peer that ignores display scaling, overlapping the menu's own labels above
 *   100% (see the KDoc there).
 * - Linux without a StatusNotifierWatcher uses Compose's own `Tray()`.
 *
 * The icon asset follows the same split: the outlined glyph where the icon is composited with
 * real alpha at a reasonable size (macOS, Linux SNI), the full-colour one everywhere else.
 *
 * @param sniConnection The Linux Status Notifier Item connection, when available.
 * @param notificationIcon The icon used for Linux SNI notifications.
 * @param unreadCount The number of unread articles displayed in the tray.
 * @param windowVisible Whether the application window is currently visible.
 * @param updateStateFlow Source of the in-app update state. Collected here, inside [KeryxTray]'s
 * own composable scope, rather than by the caller — `main.kt`'s root `application {}` composes far
 * more than the tray (the window, the Dock icon, single-instance/reopen handling), and every
 * download-progress tick used to force all of it to recompose because that scope itself read
 * `UpdateState` directly. Passing the flow instead confines each tick's recomposition to this
 * function and [trayUpdateEntry] below.
 * @param onToggle Invoked to show or hide the application window.
 * @param onQuit Invoked to quit the application.
 * @param onNotificationClicked Invoked to bring the window to front when a notification is
 * clicked, on Linux SNI (via [LinuxTray]'s `ActionInvoked` D-Bus signal) - the only platform that
 * can tell a notification click apart from a plain tray-icon click. macOS has no equivalent: AWT's
 * `TrayIcon.ActionListener` for a notification click never fires while the window is tray-hidden
 * (see known-issues.md "macOS: clicking a notification banner does not restore a tray-hidden
 * window"), and was confirmed to add nothing even when it does fire (the window merely
 * backgrounded already comes to front via macOS's own default click-to-activate behavior,
 * independent of any app code) - so [MacTray] doesn't take this parameter at all.
 * @param onTrayAction Invoked for the AWT `TrayIcon` action event (Windows, via [WindowsTray], and
 * Linux without an SNI host, via Compose's own `Tray()` `onAction`) - the path where a
 * notification click and an icon click share the same single hook, so it cannot simply be
 * [onToggle]. The call site in `main.kt` decides between hide and activate via
 * [shouldHideOnTrayAction]'s focus-plus-notification-recency heuristic.
 * @param newArticleNotifications Source of new-article notification messages.
 */
@Composable
internal fun ApplicationScope.KeryxTray(
    sniConnection: SniConnection?,
    notificationIcon: BufferedImage?,
    unreadCount: Long,
    windowVisible: Boolean,
    updateStateFlow: StateFlow<UpdateState>,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    onUpdateAction: () -> Unit,
    onNotificationClicked: () -> Unit,
    onTrayAction: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    // The outlined (white glyph + black halo) variant is only used where the icon is
    // composited with real alpha at >= 22px: the macOS menu bar and the Linux SNI panel.
    // There it stays legible on a light or dark background without any theme detection.
    //
    // Windows' notification area shows full-colour brand icons by convention and never tints
    // them, and at its 100%-DPI size of 16px the halo antialiases to grey while the white fill
    // disappears on a light taskbar. The Linux AWT fallback needs the full-colour glyph for a
    // different reason: XTrayIconPeer paints an opaque (white) box behind the icon, so a white
    // glyph is invisible on it.
    val trayIconResource =
        if (isMacOs || sniConnection != null) Res.drawable.tray_icon_outlined else Res.drawable.tray_icon
    val trayBaseImage = rememberDrawableImage(trayIconResource)
    val updateState by updateStateFlow.collectAsState()

    val tooltip = if (unreadCount > 0) "$APP_NAME ($unreadCount)" else APP_NAME
    val showLabel = stringResource(Res.string.tray_show)
    val hideLabel = stringResource(Res.string.tray_hide)
    val quitLabel = stringResource(Res.string.tray_quit)
    val toggleLabel = if (windowVisible) hideLabel else showLabel
    val updateEntry = trayUpdateEntry(updateState)

    when {
        isMacOs -> {
            if (!isTraySupported) return
            val trayBadgedImage = remember(trayBaseImage, unreadCount) {
                trayBaseImage?.let { drawUnreadDot(it, unreadCount) }
            }
            MacTray(
                image = trayBadgedImage,
                tooltip = tooltip,
                showLabel = showLabel,
                hideLabel = hideLabel,
                quitLabel = quitLabel,
                windowVisible = windowVisible,
                updateEntry = updateEntry,
                onToggle = onToggle,
                onQuit = onQuit,
                onUpdateAction = onUpdateAction,
                newArticleNotifications = newArticleNotifications,
            )
        }

        sniConnection != null -> {
            // Deliberately not gated on `isTraySupported` (i.e. AWT's SystemTray.isSupported):
            // SNI does not go through AWT at all.
            LinuxTray(
                connection = sniConnection,
                trayBaseImage = trayBaseImage,
                notificationIcon = notificationIcon,
                unreadCount = unreadCount,
                toggleLabel = toggleLabel,
                quitLabel = quitLabel,
                updateEntry = updateEntry,
                onToggle = onToggle,
                onQuit = onQuit,
                onUpdateAction = onUpdateAction,
                onNotificationClicked = onNotificationClicked,
                newArticleNotifications = newArticleNotifications,
            )
        }

        isWindows -> {
            val trayBadgedImage = remember(trayBaseImage, unreadCount) {
                trayBaseImage?.let { drawUnreadDot(it, unreadCount) }
            }
            WindowsTray(
                image = trayBadgedImage,
                tooltip = tooltip,
                toggleLabel = toggleLabel,
                quitLabel = quitLabel,
                updateEntry = updateEntry,
                onToggle = onToggle,
                onQuit = onQuit,
                onUpdateAction = onUpdateAction,
                onTrayAction = onTrayAction,
                newArticleNotifications = newArticleNotifications,
            )
        }

        isTraySupported -> {
            val trayState = rememberTrayState()
            val trayBadgedImage = remember(trayBaseImage, unreadCount) {
                trayBaseImage?.let { drawUnreadDot(it, unreadCount) }
            }
            val trayBadgedPainter = remember(trayBadgedImage) {
                trayBadgedImage?.let { BitmapPainter(it.toComposeImageBitmap()) }
            }
            trayBadgedPainter?.let { painter ->
                Tray(
                    icon = painter,
                    state = trayState,
                    tooltip = tooltip,
                    onAction = onTrayAction,
                    menu = {
                        updateEntry?.let { entry ->
                            Item(entry.label, enabled = entry.enabled, onClick = onUpdateAction)
                        }
                        Item(toggleLabel, onClick = onToggle)
                        Item(quitLabel, onClick = onQuit)
                    },
                )
                // MacTray, LinuxTray and WindowsTray consume newArticleNotifications themselves.
                // Compose's Tray() is the only thing that turns a queued TrayState notification into an
                // actual OS notification, and TrayState's channel is RENDEZVOUS - anything sent
                // while no Tray() is composed is dropped. Collecting here keeps the subscription
                // alive exactly as long as the sink is.
                LaunchedEffect(Unit) {
                    newArticleNotifications.collect { message ->
                        trayState.sendNotification(Notification(APP_NAME, message))
                    }
                }
            }
        }
    }
}

/**
 * Maps [state] to the tray's single update menu item, or `null` when nothing should be shown —
 * see [TrayUpdateEntry]'s own KDoc, and `UpdatesTab.kt`'s button-state table for the equivalent
 * mapping the settings dialog renders instead. [UpdateState.Installing] is deliberately `null`
 * too: reaching it is followed immediately by the whole app exiting (see `main.kt`'s own
 * `UpdateState.Installing` handling), so there is no meaningful window to show a tray item in.
 */
@Composable
private fun trayUpdateEntry(state: UpdateState): TrayUpdateEntry? = when (state) {
    is UpdateState.Available ->
        if (state.update.installable) {
            TrayUpdateEntry(stringResource(Res.string.tray_update_download, state.update.version), enabled = true)
        } else {
            null
        }
    is UpdateState.Downloading -> {
        val percent = roundedTrayProgressPercent(state.bytesDone, state.bytesTotal)
        TrayUpdateEntry(stringResource(Res.string.tray_update_downloading, "$percent%"), enabled = false)
    }
    is UpdateState.Verifying ->
        TrayUpdateEntry(stringResource(Res.string.tray_update_downloading, "100%"), enabled = false)
    is UpdateState.Ready ->
        TrayUpdateEntry(stringResource(Res.string.tray_update_restart, state.update.version), enabled = true)
    is UpdateState.Failed -> TrayUpdateEntry(stringResource(Res.string.tray_update_failed), enabled = true)
    UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate, is UpdateState.Installing -> null
}
