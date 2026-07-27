package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * macOS-only replacement for the Compose `Tray()` composable.
 *
 * `Tray()` wires the icon's popup menu via `TrayIcon.setPopupMenu()`, which on
 * macOS shows the popup on *any* click (left or right) - a long-standing
 * AWT/macOS limitation (Compose upstream has an unresolved TODO for this). To
 * get "right-click = menu, left-click = toggle", this bypasses `setPopupMenu`
 * and drives a raw `TrayIcon`/`PopupMenu` pair with a manual `MouseListener`.
 */
/**
 * Manages the macOS system tray icon, menu actions, and article notifications.
 *
 * @param image The tray icon image, or `null` to leave the tray uninitialized.
 * @param windowVisible Whether the application window is currently visible.
 * @param showLabel Label for showing the window.
 * @param hideLabel Label for hiding the window.
 * @param newArticleNotifications Notifications displayed through the tray icon.
 */
@Composable
internal fun MacTray(
    image: Image?,
    tooltip: String,
    showLabel: String,
    hideLabel: String,
    quitLabel: String,
    windowVisible: Boolean,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    val image = image ?: return

    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnQuit by rememberUpdatedState(onQuit)

    // TrayIcon isn't a java.awt.Component, so PopupMenu.show(...) needs some
    // origin Component. This Frame exists only to host the PopupMenu and is
    // 0x0 so it's never visually noticeable. It must still be isVisible=true,
    // since PopupMenu.show() requires origin.isShowing() to be true, and
    // pack() alone (creating a peer without showing it) isn't enough.
    val dummyFrame = remember {
        Frame().apply {
            isUndecorated = true
            setSize(0, 0)
            location = java.awt.Point(0, 0)
            setFocusableWindowState(false)
            isVisible = true
        }
    }
    val toggleItem = remember { MenuItem() }
    val quitItem = remember { MenuItem() }
    val popupMenu = remember {
        PopupMenu().apply {
            add(toggleItem)
            add(quitItem)
        }
    }
    DisposableEffect(dummyFrame, popupMenu) {
        dummyFrame.add(popupMenu)
        onDispose { dummyFrame.dispose() }
    }

    val trayIcon = remember { TrayIcon(image).apply { isImageAutoSize = true } }
    LaunchedEffect(trayIcon, image) {
        trayIcon.image = image
    }
    DisposableEffect(trayIcon, popupMenu) {
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when (e.button) {
                    MouseEvent.BUTTON1 -> currentOnToggle()
                    MouseEvent.BUTTON3 -> {
                        // TrayIcon's MouseEvent x/y are already screen-absolute
                        // coordinates. Translate them into dummyFrame's local
                        // coordinate space (rather than assuming the frame
                        // stays exactly at (0,0)) so the menu opens at the
                        // click location instead of wherever the frame is.
                        val origin = dummyFrame.locationOnScreen
                        popupMenu.show(dummyFrame, e.xOnScreen - origin.x, e.yOnScreen - origin.y)
                    }
                }
            }
        }
        trayIcon.addMouseListener(listener)
        val systemTray = SystemTray.getSystemTray()
        systemTray.add(trayIcon)
        onDispose {
            trayIcon.removeMouseListener(listener)
            systemTray.remove(trayIcon)
        }
    }

    LaunchedEffect(toggleItem) {
        toggleItem.addActionListener { currentOnToggle() }
    }
    LaunchedEffect(quitItem) {
        quitItem.addActionListener { currentOnQuit() }
    }
    LaunchedEffect(trayIcon, tooltip) {
        trayIcon.toolTip = tooltip
    }
    LaunchedEffect(toggleItem, windowVisible, showLabel, hideLabel) {
        toggleItem.label = if (windowVisible) hideLabel else showLabel
    }
    LaunchedEffect(quitItem, quitLabel) {
        quitItem.label = quitLabel
    }

    // Tray()'s own composable body is what turns a queued TrayState notification
    // into an actual TrayIcon.displayMessage(...) call; since MacTray replaces
    // Tray() entirely on macOS, it must consume newArticleNotifications itself.
    LaunchedEffect(trayIcon) {
        newArticleNotifications.collect { message ->
            trayIcon.displayMessage("Keryx", message, TrayIcon.MessageType.NONE)
        }
    }
}
