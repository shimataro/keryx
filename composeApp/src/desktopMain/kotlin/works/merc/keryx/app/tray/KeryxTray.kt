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
import works.merc.keryx.app.isMacOs
import works.merc.keryx.app.rememberDrawableImage
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.tray_hide
import works.merc.keryx.app.resources.tray_icon_outlined
import works.merc.keryx.app.resources.tray_quit
import works.merc.keryx.app.resources.tray_show
import java.awt.image.BufferedImage

/**
 * The application's system-tray icon, dispatching to the per-platform implementation.
 *
 * - macOS uses [MacTray] (a raw AWT `TrayIcon`, so a left click toggles the window instead of
 *   opening the menu).
 * - Linux uses [LinuxTray] whenever [sniConnection] is non-null, because AWT's X11 tray cannot
 *   draw a transparent icon (see the KDoc there).
 * - Everything else - and Linux without a StatusNotifierWatcher - uses Compose's own `Tray()`.
 */
@Composable
internal fun ApplicationScope.KeryxTray(
    sniConnection: SniConnection?,
    notificationIcon: BufferedImage?,
    unreadCount: Long,
    windowVisible: Boolean,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    val trayState = rememberTrayState()
    // The tray uses the outlined (white glyph + black halo) variant on every OS: it
    // stays legible on a light or dark menu bar / panel / taskbar without any theme
    // detection. The window's own title-bar/taskbar icon keeps the full-color glyph.
    val trayBaseImage = rememberDrawableImage(Res.drawable.tray_icon_outlined)

    val tooltip = if (unreadCount > 0) "Keryx ($unreadCount)" else "Keryx"
    val showLabel = stringResource(Res.string.tray_show)
    val hideLabel = stringResource(Res.string.tray_hide)
    val quitLabel = stringResource(Res.string.tray_quit)
    val toggleLabel = if (windowVisible) hideLabel else showLabel

    if (!isMacOs && sniConnection == null) {
        // MacTray and LinuxTray consume newArticleNotifications themselves; only Compose's
        // Tray() turns a queued TrayState notification into an actual OS notification, and
        // its composable body is the only place that happens.
        LaunchedEffect(Unit) {
            newArticleNotifications.collect { message ->
                trayState.sendNotification(Notification("Keryx", message))
            }
        }
    }

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
                newArticleNotifications = newArticleNotifications,
            )
        }

        isTraySupported -> {
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
                    onAction = onToggle,
                    menu = {
                        Item(toggleLabel, onClick = onToggle)
                        Item(quitLabel, onClick = onQuit)
                    },
                )
            }
        }
    }
}
