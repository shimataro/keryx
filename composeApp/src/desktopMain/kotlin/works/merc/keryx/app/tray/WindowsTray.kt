package works.merc.keryx.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.isTraySupported
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Component
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * The Windows tray icon's popup menu, as Swing widgets.
 *
 * Swing rather than the `java.awt.PopupMenu` that Compose's own `Tray()` builds: the JDK's Windows
 * menu peer fills `MEASUREITEMSTRUCT.itemHeight` from `FontMetrics.getHeight()` (Java user space)
 * while painting the labels with a device-pixel-scaled HFONT, so above 100% display scaling every
 * row is `1 / scale` as tall as its own text and the two entries overlap. Same JDK defect that
 * moved the context menus off AWT — see `platform/NativeMenu.desktop.kt` and `known-issues.md`.
 *
 * Internal rather than private so tests can check what actually ended up in the menu, mirroring
 * how `NativeMenuTest` inspects `SwingPopupHandle.popupMenu`.
 *
 * @param onToggle Invoked when the show/hide entry is chosen.
 * @param onQuit Invoked when the quit entry is chosen.
 */
internal class WindowsTrayMenu(
    onToggle: () -> Unit,
    onQuit: () -> Unit,
) {
    private val toggleItem = JMenuItem().apply { addActionListener { onToggle() } }
    private val quitItem = JMenuItem().apply { addActionListener { onQuit() } }

    /** Internal rather than private so tests can check what actually ended up in the menu. */
    internal val popupMenu = JPopupMenu().apply {
        // The tray menu is not drawn over the article reader's WebView the way a context menu is,
        // but a lightweight popup is still clipped to its invoker window — which here is a 1x1
        // frame, so it would have nothing to draw into at all.
        isLightWeightPopupEnabled = false
        add(toggleItem)
        add(quitItem)
    }

    /**
     * Pushes the current labels onto the already-built widgets.
     *
     * @param toggle The label for the show/hide entry, which flips with the window's visibility.
     * @param quit The label for the quit entry.
     */
    fun setLabels(toggle: String, quit: String) {
        toggleItem.text = toggle
        quitItem.text = quit
    }

    /**
     * Shows the menu.
     *
     * @param invoker The component the menu is positioned against.
     * @param x The horizontal display coordinate within [invoker].
     * @param y The vertical display coordinate within [invoker].
     */
    fun show(invoker: Component, x: Int, y: Int) {
        popupMenu.show(invoker, x, y)
    }
}

/**
 * Windows-only replacement for the Compose `Tray()` composable.
 *
 * `Tray()` wires the icon's menu through `TrayIcon.setPopupMenu()`, i.e. a `java.awt.PopupMenu`,
 * which the JDK renders with squashed, overlapping items on any HiDPI Windows desktop (see
 * [WindowsTrayMenu]). This drives a raw `TrayIcon` instead and opens a [WindowsTrayMenu] on
 * right-click, the same shape as [MacTray] — which bypasses `Tray()` for its own, unrelated
 * reason.
 *
 * @param image The tray icon image, already badged with the unread count; when `null`, no tray UI
 * is displayed.
 * @param tooltip The tray icon tooltip.
 * @param toggleLabel The menu label for showing or hiding the window, per its current visibility.
 * @param quitLabel The menu label for quitting the application.
 * @param onToggle Called when the toggle menu item is activated.
 * @param onQuit Called when the quit menu item is activated.
 * @param onTrayAction Called for the `TrayIcon` action event — a double click on the icon or a
 * click on a notification balloon, which Windows cannot tell apart. Kept wired exactly as
 * Compose's `Tray()` `onAction` was, so `main.kt`'s [shouldHideOnTrayAction] heuristic still sees
 * the same events.
 * @param newArticleNotifications Notifications to display as Windows notifications.
 */
@Composable
internal fun WindowsTray(
    image: Image?,
    tooltip: String,
    toggleLabel: String,
    quitLabel: String,
    onToggle: () -> Unit,
    onQuit: () -> Unit,
    onTrayAction: () -> Unit,
    newArticleNotifications: SharedFlow<String>,
) {
    if (!isTraySupported) return
    val image = image ?: return

    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentOnQuit by rememberUpdatedState(onQuit)
    val currentOnTrayAction by rememberUpdatedState(onTrayAction)

    val menu = remember {
        WindowsTrayMenu(onToggle = { currentOnToggle() }, onQuit = { currentOnQuit() })
    }

    // A JPopupMenu needs an invoker Component, and a TrayIcon is not one. Unlike MacTray's
    // equivalent frame this one stays hidden until a right-click and is deliberately focusable:
    // an AWT PopupMenu runs its own native modal loop and dismisses itself, whereas a JPopupMenu
    // closes on an outside click only if its owning window can hold — and therefore lose — focus.
    // Window.Type.UTILITY keeps it off the taskbar and out of Alt+Tab; 1x1 rather than 0x0 so it
    // is a real, focusable window, and it is moved under the cursor where the menu covers it.
    val invokerFrame = remember {
        JFrame().apply {
            isUndecorated = true
            type = Window.Type.UTILITY
            isAlwaysOnTop = true
            setSize(1, 1)
        }
    }
    DisposableEffect(invokerFrame, menu) {
        val listener = object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit

            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {
                invokerFrame.isVisible = false
            }

            override fun popupMenuCanceled(e: PopupMenuEvent) {
                invokerFrame.isVisible = false
            }
        }
        menu.popupMenu.addPopupMenuListener(listener)
        onDispose {
            menu.popupMenu.removePopupMenuListener(listener)
            invokerFrame.dispose()
        }
    }

    val trayIcon = remember { TrayIcon(image).apply { isImageAutoSize = true } }
    LaunchedEffect(trayIcon, image) {
        trayIcon.image = image
    }
    LaunchedEffect(trayIcon, tooltip) {
        trayIcon.toolTip = tooltip
    }
    LaunchedEffect(menu, toggleLabel, quitLabel) {
        menu.setLabels(toggle = toggleLabel, quit = quitLabel)
    }

    DisposableEffect(trayIcon, menu, invokerFrame) {
        val mouseListener = object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                // isPopupTrigger is the platform-correct test; BUTTON3 is kept as a belt-and-braces
                // fallback because TrayIcon's events do not come from a real component peer.
                if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
                // TrayIcon's MouseEvent x/y are already screen-absolute. Park the invoker under
                // the cursor and open at its origin, so the menu lands exactly where the click
                // did; JPopupMenu flips it back on-screen by itself near the taskbar.
                invokerFrame.setLocation(e.xOnScreen, e.yOnScreen)
                invokerFrame.isVisible = true
                menu.show(invokerFrame, 0, 0)
                invokerFrame.toFront()
            }
        }
        val actionListener = ActionListener { currentOnTrayAction() }
        trayIcon.addMouseListener(mouseListener)
        trayIcon.addActionListener(actionListener)
        val systemTray = SystemTray.getSystemTray()
        systemTray.add(trayIcon)
        onDispose {
            trayIcon.removeMouseListener(mouseListener)
            trayIcon.removeActionListener(actionListener)
            systemTray.remove(trayIcon)
        }
    }

    // Tray()'s own composable body is what turns a queued TrayState notification into an actual
    // TrayIcon.displayMessage(...) call; since this replaces Tray() entirely on Windows, it must
    // consume newArticleNotifications itself — exactly as MacTray and LinuxTray do.
    LaunchedEffect(trayIcon) {
        newArticleNotifications.collect { message ->
            trayIcon.displayMessage("Keryx", message, TrayIcon.MessageType.NONE)
        }
    }
}
