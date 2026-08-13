package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import kotlinx.coroutines.flow.SharedFlow
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.drawUnreadDot
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.rememberDrawableImage
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.tray_hide
import works.merc.keryx.app.resources.tray_icon
import works.merc.keryx.app.resources.tray_icon_outlined
import works.merc.keryx.app.resources.tray_quit
import works.merc.keryx.app.resources.tray_show
import java.awt.image.BufferedImage

/**
 * The application's system-tray icon, dispatching to the per-platform implementation, and
 * configuring its icon, actions, unread badge, and notifications.
 *
 * - macOS uses [MacTray] (a raw AWT `TrayIcon`, so a left click toggles the window instead of
 *   opening the menu).
 * - Linux uses [LinuxTray] whenever [sniConnection] is non-null, because AWT's X11 tray cannot
 *   draw a transparent icon (see the KDoc there).
 * - Everything else - and Linux without a StatusNotifierWatcher - uses Compose's own `Tray()`.
 *
 * The icon asset follows the same split: the outlined glyph where the icon is composited with
 * real alpha at a reasonable size (macOS, Linux SNI), the full-colour one everywhere else.
 *
 * @param sniConnection The Linux Status Notifier Item connection, when available.
 * @param notificationIcon The icon used for Linux SNI notifications.
 * @param unreadCount The number of unread articles displayed in the tray.
 * @param windowVisible Whether the application window is currently visible.
 * @param onToggle Invoked to show or hide the application window.
 * @param onQuit Invoked to quit the application.
 * @param onNotificationClicked Invoked to bring the window to front when a notification is
 * clicked, on the platforms that can tell a notification click apart from a plain tray-icon
 * click: macOS (via [MacTray]'s separate `ActionListener`) and Linux SNI (via [LinuxTray]'s
 * `ActionInvoked` D-Bus signal).
 * @param onTrayAction Invoked for Compose's own `Tray()` `onAction` (Windows, and Linux without
 * an SNI host) - the fallback path where a notification click and an icon click share the same
 * single hook, so it cannot simply be [onToggle]; see the call site in `main.kt` for the
 * focus-aware logic that covers both.
 * @param newArticleNotifications Source of new-article notification messages.
 */
@Composable
internal fun ApplicationScope.KeryxTray(
    sniConnection: SniConnection?,
    notificationIcon: BufferedImage?,
    unreadCount: Long,
    windowVisible: Boolean,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
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

    val tooltip = if (unreadCount > 0) "Keryx ($unreadCount)" else "Keryx"
    val showLabel = stringResource(Res.string.tray_show)
    val hideLabel = stringResource(Res.string.tray_hide)
    val quitLabel = stringResource(Res.string.tray_quit)
    val toggleLabel = if (windowVisible) hideLabel else showLabel

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
                onToggle = onToggle,
                onQuit = onQuit,
                onNotificationClicked = onNotificationClicked,
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
                onToggle = onToggle,
                onQuit = onQuit,
                onNotificationClicked = onNotificationClicked,
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
                        Item(toggleLabel, onClick = onToggle)
                        Item(quitLabel, onClick = onQuit)
                    },
                )
                // MacTray and LinuxTray consume newArticleNotifications themselves. Compose's
                // Tray() is the only thing that turns a queued TrayState notification into an
                // actual OS notification, and TrayState's channel is RENDEZVOUS - anything sent
                // while no Tray() is composed is dropped. Collecting here keeps the subscription
                // alive exactly as long as the sink is.
                LaunchedEffect(Unit) {
                    newArticleNotifications.collect { message ->
                        trayState.sendNotification(Notification("Keryx", message))
                    }
                }
            }
        }
    }
}
