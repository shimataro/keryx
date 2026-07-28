package works.merc.keryx.app.appmenu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.types.UInt32
import org.koin.compose.koinInject
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.tray.DBusMenu
import works.merc.keryx.app.ui.AppMenuBar
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.MenuBarToggle
import java.awt.KeyboardFocusManager
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "AppMenuBarHost"

/** XID resolution retry budget: WM reparenting can lag `setVisible(true)` by a frame or two. */
private const val XID_RETRY_ATTEMPTS = 20
private const val XID_RETRY_DELAY_MS = 100L

/**
 * Wires the application menu bar to the KDE Global Menu, replacing the plain `AppMenuBar` call site.
 *
 * When [appMenuConnection] is `null` (macOS / Windows / non-KDE Linux) this is exactly today's
 * behavior: the in-window `AppMenuBar` renders and nothing else happens. When a registrar
 * connection exists it additionally:
 * - exports the menu tree over D-Bus (kept live even while the in-window bar is hidden),
 * - resolves this window's XID (once shown) and calls `RegisterWindow`,
 * - hides the in-window bar only after registration actually succeeds (unless the user set an
 *   explicit preference), with Ctrl+M and an in-menu "Show Menu Bar" checkbox to bring it back,
 * - installs a global shortcut dispatcher whenever the bar is hidden so Ctrl+N/W/,/Q/R keep working
 *   (mutually exclusive with the native accelerators — see [MenuShortcutDispatcher]).
 *
 * @param resolvedXid holder written with the registered XID so `main.kt`'s shutdown hook can
 *   unregister it (`AppMenuConnection.close`).
 */
@Composable
internal fun FrameWindowScope.AppMenuBarHost(
    appMenuConnection: AppMenuConnection?,
    windowVisible: Boolean,
    onCloseWindow: () -> Unit,
    onQuit: () -> Unit,
    resolvedXid: AtomicReference<Long?>,
) {
    if (appMenuConnection == null) {
        AppMenuBar(onCloseWindow = onCloseWindow, onQuit = onQuit)
        return
    }

    val settingsRepository = koinInject<SettingsRepository>()

    // Explicit user preference (null = auto: shown until this app's RegisterWindow succeeds).
    val savedPreference = remember { settingsRepository.getLocalSettings().appMenuBarVisible }
    var menuBarVisible by remember { mutableStateOf(savedPreference ?: true) }
    var registered by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }

    // Latest tree, shared by the D-Bus exporter and the shortcut dispatcher. AtomicReference because
    // the dispatcher reads it from the AWT event thread.
    val treeRef = remember { AtomicReference<AppMenuRoot?>(null) }

    val exporter = remember(appMenuConnection) {
        AppMenuDBusMenu(
            objectPath = AppMenuConnection.MENU_PATH,
            onLayoutUpdated = { revision ->
                appMenuConnection.emit {
                    DBusMenu.LayoutUpdated(AppMenuConnection.MENU_PATH, UInt32(revision.toLong()), APPMENU_ROOT_ID)
                }
            },
        )
    }

    fun setMenuBarVisible(visible: Boolean) {
        menuBarVisible = visible
        // Persist the explicit choice so it survives restart (see LocalSettings.appMenuBarVisible).
        settingsRepository.saveLocalSettings(settingsRepository.getLocalSettings().withMenuBarVisible(visible))
    }

    val menuBarToggle = MenuBarToggle(visible = menuBarVisible, onToggle = ::setMenuBarVisible)

    // Export the menu object for the connection's lifetime.
    DisposableEffect(appMenuConnection, exporter) {
        exported = runCatching { appMenuConnection.exportObject(exporter) }
            .onFailure { Log.warn(LOG_TAG, "Could not export the dbusmenu object", it) }
            .isSuccess
        onDispose { appMenuConnection.detach() }
    }

    // Resolve the XID once the window is actually shown, then register. Guarded so it runs once.
    // Skips entirely if the export above failed — registering with the registrar while pointing it
    // at a nonexistent dbusmenu object would leave the user with no menu at all.
    LaunchedEffect(appMenuConnection, windowVisible, exported) {
        if (!windowVisible || registered || !exported) return@LaunchedEffect
        val xid = withContext(Dispatchers.IO) { resolveXidWithRetry() }
        if (xid == null) {
            Log.warn(LOG_TAG, "Could not resolve the window XID; keeping the in-window menu bar")
            return@LaunchedEffect
        }
        val ok = withContext(Dispatchers.IO) { appMenuConnection.registerWindow(xid) }
        if (!ok) {
            Log.warn(LOG_TAG, "AppMenu registration failed; keeping the in-window menu bar")
            return@LaunchedEffect
        }
        registered = true
        resolvedXid.set(xid)
        // Auto-hide only when the user expressed no explicit preference; don't persist the auto value.
        if (savedPreference == null) menuBarVisible = false
    }

    // Re-register when the registrar reappears (a plasmashell/kded restart).
    LaunchedEffect(appMenuConnection) {
        appMenuConnection.reregisterRequests.collect {
            val xid = resolvedXid.get() ?: return@collect
            withContext(Dispatchers.IO) { appMenuConnection.registerWindow(xid) }
        }
    }

    // Host-initiated clicks arrive on a dbus-java worker thread and are re-published on clickedIds;
    // dispatch them here on the UI thread via the *latest* table (nodeFor reads the current layout).
    LaunchedEffect(exporter) {
        exporter.clickedIds.collect { id -> exporter.nodeFor(id)?.invokeAction() }
    }

    // Install the global shortcut dispatcher exactly while the in-window bar is hidden, and remove it
    // the instant it becomes visible again — mutually exclusive with the native accelerators.
    DisposableEffect(menuBarVisible) {
        if (menuBarVisible) {
            onDispose { }
        } else {
            val dispatcher = MenuShortcutDispatcher { treeRef.get() }
            val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            focusManager.addKeyEventDispatcher(dispatcher)
            onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
        }
    }

    AppMenuBar(
        onCloseWindow = onCloseWindow,
        onQuit = onQuit,
        menuBarToggle = menuBarToggle,
        renderMenuBar = menuBarVisible,
        onTreeChanged = { tree ->
            treeRef.set(tree)
            exporter.updateState(tree)
        },
    )

    // Compose's own MenuBar composable calls ComposeWindow.setJMenuBar(...) on both mount and
    // dispose (confirmed by decompiling androidx.compose.ui.window's desktop implementation) but
    // never calls revalidate()/repaint() itself — a known Swing gap (JRootPane.setJMenuBar only
    // touches the layered pane's component list). Without this, toggling menuBarVisible either
    // leaves a stale, input-dead rendering of the previous menu bar (hiding) or silently attaches
    // a new one with no visible change (showing again via Ctrl+M / the checkbox). LaunchedEffect
    // runs after Compose has applied the composition (including the child MenuBar's own
    // mount/dispose effect), so the JMenuBar change has already landed by the time this runs.
    LaunchedEffect(menuBarVisible) {
        window.revalidate()
        window.repaint()
    }
}

/**
 * Resolves this window's XID, retrying briefly because WM reparenting can lag `setVisible(true)`.
 * Blocking X calls run inside [X11WindowId]; call this off the UI thread.
 */
private suspend fun resolveXidWithRetry(): Long? {
    repeat(XID_RETRY_ATTEMPTS) {
        X11WindowId.findOwnWindowId()?.let { return it }
        delay(XID_RETRY_DELAY_MS)
    }
    return null
}
