package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.SharedFlow
import works.merc.keryx.app.core.APP_NAME
import java.awt.Component
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * The macOS tray icon's popup menu, as raw AWT widgets — built and mutated outside composition,
 * the same shape as [WindowsTrayMenu] (see that class's own KDoc for why the AWT-menu operations
 * live in a plain class rather than inline in the composable: testability, without needing a real
 * display or tray).
 *
 * Internal rather than private so tests can check what actually ended up in the menu, mirroring
 * `WindowsTrayMenuTest`.
 *
 * @param onToggle Invoked when the toggle entry is chosen.
 * @param onQuit Invoked when the quit entry is chosen.
 */
internal class MacTrayMenu(
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    onUpdateAction: () -> Unit = {},
) {
    private val toggleItem = MenuItem().apply { addActionListener { onToggle() } }
    private val quitItem = MenuItem().apply { addActionListener { onQuit() } }
    private val updateItem = MenuItem().apply { addActionListener { onUpdateAction() } }

    // "-" is the exact idiom java.awt.Menu.addSeparator() itself uses (see NativeMenu.desktop.kt).
    private val updateSeparator = MenuItem("-")

    /** Internal rather than private so tests can check what actually ended up in the menu. */
    internal val popupMenu = PopupMenu().apply {
        add(updateItem)
        add(updateSeparator)
        add(toggleItem)
        add(quitItem)
    }

    /**
     * Pushes the current labels onto the already-built widgets.
     *
     * @param toggle The label for the toggle entry — already resolved by the caller from
     * `windowVisible`/`showLabel`/`hideLabel`, since that resolution is presentation logic tied to
     * Compose state, not something this AWT-facing class needs to know about.
     * @param quit The label for the quit entry.
     */
    fun setLabels(toggle: String, quit: String) {
        toggleItem.label = toggle
        quitItem.label = quit
    }

    /**
     * Pushes the in-app update entry's label and enabled state onto the already-built widget. The
     * entry itself is a permanent part of the menu (see [TrayMenuState.update]'s own KDoc): every
     * `UpdateState` maps to a label, and one with nothing to act on is shown disabled rather than
     * removed.
     */
    fun setUpdateEntry(entry: TrayUpdateEntry) {
        updateItem.label = entry.label
        updateItem.isEnabled = entry.enabled
    }

    /**
     * Shows the menu.
     *
     * @param origin The component the menu is positioned against.
     * @param x The horizontal coordinate within [origin].
     * @param y The vertical coordinate within [origin].
     */
    fun show(origin: Component, x: Int, y: Int) {
        popupMenu.show(origin, x, y)
    }
}

/**
 * macOS-only replacement for the Compose `Tray()` composable.
 *
 * `Tray()` wires the icon's popup menu via `TrayIcon.setPopupMenu()`, which on
 * macOS shows the popup on *any* click (left or right) - a long-standing
 * AWT/macOS limitation (Compose upstream has an unresolved TODO for this). To
 * get "right-click = menu, left-click = toggle", this bypasses `setPopupMenu`
 * and drives a raw `TrayIcon`/[MacTrayMenu] pair with a manual `MouseListener`.
 *
 * @param image The tray icon image; when `null`, no tray UI is displayed.
 * @param tooltip The tray icon tooltip.
 * @param showLabel The menu label used when the window is hidden.
 * @param hideLabel The menu label used when the window is visible.
 * @param quitLabel The menu label for quitting the application.
 * @param windowVisible Whether the application window is currently visible.
 * @param onToggle Called when the tray icon or toggle menu item is activated.
 * @param onQuit Called when the quit menu item is activated.
 * @param newArticleNotifications Notifications to display as macOS user notifications.
 */
@Composable
internal fun MacTray(
    image: Image?,
    tooltip: String,
    showLabel: String,
    hideLabel: String,
    quitLabel: String,
    windowVisible: Boolean,
    updateEntry: TrayUpdateEntry,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    onUpdateAction: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    val image = image ?: return

    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnQuit by rememberUpdatedState(onQuit)
    val currentOnUpdateAction by rememberUpdatedState(onUpdateAction)

    val menu = remember {
        MacTrayMenu(
            onToggle = { currentOnToggle() },
            onQuit = { currentOnQuit() },
            onUpdateAction = { currentOnUpdateAction() },
        )
    }

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
    DisposableEffect(dummyFrame, menu) {
        dummyFrame.add(menu.popupMenu)
        onDispose { dummyFrame.dispose() }
    }

    val trayIcon = remember { TrayIcon(image).apply { isImageAutoSize = true } }
    LaunchedEffect(trayIcon, image) {
        trayIcon.image = image
    }
    DisposableEffect(trayIcon, menu) {
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
                        menu.show(dummyFrame, e.xOnScreen - origin.x, e.yOnScreen - origin.y)
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

    LaunchedEffect(trayIcon, tooltip) {
        trayIcon.toolTip = tooltip
    }
    LaunchedEffect(menu, windowVisible, showLabel, hideLabel, quitLabel) {
        menu.setLabels(toggle = if (windowVisible) hideLabel else showLabel, quit = quitLabel)
    }
    LaunchedEffect(menu, updateEntry) {
        menu.setUpdateEntry(updateEntry)
    }

    // Tray()'s own composable body is what turns a queued TrayState notification
    // into an actual TrayIcon.displayMessage(...) call; since MacTray replaces
    // Tray() entirely on macOS, it must consume newArticleNotifications itself.
    LaunchedEffect(trayIcon) {
        newArticleNotifications.collect { message ->
            trayIcon.displayMessage(APP_NAME, message, TrayIcon.MessageType.NONE)
        }
    }
}
