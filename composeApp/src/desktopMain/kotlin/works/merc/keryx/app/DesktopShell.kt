package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import java.awt.Desktop
import java.awt.Frame
import java.awt.Image
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener

private const val LOG_TAG = "Main"

// These helpers act on the real AWT Frame / Taskbar / Desktop, which a headless test run cannot
// provide, so they have no unit tests; verify them by running the app.

/**
 * Shows this window, un-minimizes it and brings it to the front with focus. [beforeToFront] runs
 * after it is shown and restored, just before it is fronted.
 */
internal fun Frame.showRestoredAndFocus(beforeToFront: () -> Unit = {}) {
    isVisible = true
    extendedState = extendedState and Frame.ICONIFIED.inv()
    beforeToFront()
    toFront()
    requestFocus()
}

/**
 * Applies the provided image as the taskbar or Dock icon when supported.
 *
 * @param image The icon image to apply, or `null` if no image is available.
 */
internal fun applyBrandedDockIcon(image: Image?) {
    if (image == null || !Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        taskbar.iconImage = image
    }
}

/**
 * Registers a desktop handler when the requested action is supported.
 *
 * @param action The desktop action required for registration.
 * @param label The handler name used in failure logs.
 * @param install Registers the handler on the supported desktop instance.
 */
internal fun installDesktopHandler(action: Desktop.Action, label: String, install: (Desktop) -> Unit) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)) {
            install(Desktop.getDesktop())
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not install the $label handler", it) }
}

/**
 * Installs the macOS application-level handlers, in order: the About and Settings… items of the app
 * menu, `keryx://` URIs, opened `.opml` files, and the Dock/Finder "reopen" event. Each is skipped
 * where its [Desktop.Action] is unsupported (in practice, everywhere but macOS). Touching [Desktop]
 * initializes AWT, so call this only after the `apple.awt.*` system properties are set.
 *
 * @param menuController Receives the About / Settings menu commands.
 * @param onOAuthCallback Called with a received `keryx://` callback URI.
 * @param onOpmlFile Called with the path of each opened `.opml` file.
 * @param onReopen Called when the OS asks an already-running app to reopen.
 */
internal fun installAppEventHandlers(
    menuController: MenuController,
    onOAuthCallback: (String) -> Unit,
    onOpmlFile: (String) -> Unit,
    onReopen: () -> Unit,
) {
    // Replace the JVM's default "About" panel (which shows "java" + the JVM version) with our
    // own About dialog, and route the native "Settings…" (⌘,) item to our Settings screen. Both
    // live in the macOS app (Keryx) menu, so they are omitted from AppMenuBar there. macOS-only in
    // practice (APP_ABOUT / APP_PREFERENCES are unsupported elsewhere).
    installDesktopHandler(Desktop.Action.APP_ABOUT, "About menu") {
        it.setAboutHandler { menuController.send(MenuCommand.About) }
    }
    installDesktopHandler(Desktop.Action.APP_PREFERENCES, "Preferences menu") {
        it.setPreferencesHandler { menuController.send(MenuCommand.OpenSettings) }
    }

    // Install the in-process URI handler (macOS). Windows/Linux receive the URI as a
    // command-line argument instead, forwarded through SingleInstanceCoordinator.
    installDesktopHandler(Desktop.Action.APP_OPEN_URI, "URI") {
        it.setOpenURIHandler { event ->
            val uri = event.uri.toString()
            if (uri.startsWith("$CUSTOM_URI_SCHEME://")) onOAuthCallback(uri)
        }
    }

    // Install the in-process file-open handler (macOS). A double-clicked / "Open With"-launched
    // .opml file arrives here via an Apple Event whether or not Keryx was already running — unlike
    // Windows/Linux, which only ever deliver it as argv on a genuinely new process (see the
    // single-instance dispatch in main()).
    installDesktopHandler(Desktop.Action.APP_OPEN_FILE, ".opml file-open") { desktop ->
        desktop.setOpenFileHandler { event ->
            event.files
                .filter { it.name.endsWith(".opml", ignoreCase = true) }
                .forEach { file -> onOpmlFile(file.absolutePath) }
        }
    }

    // macOS: a second Finder/Dock double-click while Keryx is already running never spawns a
    // second process - Launch Services enforces single-instance for a plain .app bundle itself -
    // it delivers a native "reopen" Apple Event to this process instead. SingleInstanceCoordinator's
    // file-lock/socket handoff in main() only ever sees a genuine second process (Windows/Linux), so
    // without this listener the window-restore + Dock-icon-reapply logic gated on activationRequests
    // (see onReopen) never runs on macOS, leaving the window hidden and the Dock showing a generic icon.
    installDesktopHandler(Desktop.Action.APP_EVENT_REOPENED, "reopen") {
        it.addAppEventListener(AppReopenedListener { onReopen() })
    }
}
