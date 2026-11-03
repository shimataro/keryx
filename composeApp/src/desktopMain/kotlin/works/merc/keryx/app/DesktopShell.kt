package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import java.awt.Desktop
import java.awt.Frame
import java.awt.Image
import java.awt.Taskbar
import java.awt.desktop.AppReopenedListener
import java.io.File
import java.net.URI

private const val LOG_TAG = "Main"

// These helpers act on the real AWT Frame / Taskbar / Desktop, which a headless test run cannot
// provide, so they have no unit tests; verify them by running the app. The exception is the
// application-event registration (installAppEventHandlers), which goes through AppEventRegistry so
// a test can stand in for the Desktop.

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
 * The application-level events [installAppEventHandlers] subscribes to, as plain callbacks. It
 * exists so the registration can be tested without a real [Desktop]: AWT's event types cannot be
 * built headless, so none of them cross this seam.
 */
internal interface AppEventRegistry {
    /** Whether the platform delivers the event behind [action]. */
    fun isSupported(action: Desktop.Action): Boolean

    /** Sets the handler of the app menu's About item. */
    fun onAbout(handler: () -> Unit)

    /** Sets the handler of the app menu's Settings… item. */
    fun onPreferences(handler: () -> Unit)

    /** Sets the handler of a received URI. */
    fun onOpenUri(handler: (URI) -> Unit)

    /** Sets the handler of files opened with the app. */
    fun onOpenFiles(handler: (List<File>) -> Unit)

    /** Adds a listener for the OS asking an already-running app to reopen. */
    fun onReopened(handler: () -> Unit)
}

/** The [AppEventRegistry] over the real [desktop]. */
private class AwtAppEventRegistry(private val desktop: Desktop) : AppEventRegistry {
    override fun isSupported(action: Desktop.Action): Boolean = desktop.isSupported(action)

    override fun onAbout(handler: () -> Unit) = desktop.setAboutHandler { handler() }

    override fun onPreferences(handler: () -> Unit) = desktop.setPreferencesHandler { handler() }

    override fun onOpenUri(handler: (URI) -> Unit) = desktop.setOpenURIHandler { handler(it.uri) }

    override fun onOpenFiles(handler: (List<File>) -> Unit) = desktop.setOpenFileHandler { handler(it.files) }

    override fun onReopened(handler: () -> Unit) = desktop.addAppEventListener(AppReopenedListener { handler() })
}

/**
 * The [AppEventRegistry] of this machine's [Desktop], or `null` where AWT has none. Touching
 * [Desktop] initializes AWT.
 */
internal fun awtAppEventRegistry(): AppEventRegistry? =
    runCatching { if (Desktop.isDesktopSupported()) AwtAppEventRegistry(Desktop.getDesktop()) else null }
        .onFailure { Log.warn(LOG_TAG, "Could not reach the desktop application events", it) }
        .getOrNull()

/**
 * Registers an application-event handler when the requested action is supported.
 *
 * @param registry The registry to register on.
 * @param action The desktop action required for registration.
 * @param label The handler name used in failure logs.
 * @param install Registers the handler on [registry].
 */
internal fun installDesktopHandler(
    registry: AppEventRegistry,
    action: Desktop.Action,
    label: String,
    install: (AppEventRegistry) -> Unit,
) {
    runCatching {
        if (registry.isSupported(action)) install(registry)
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
 * @param registry Where the handlers are registered; `null` (no [Desktop] here) registers nothing.
 */
internal fun installAppEventHandlers(
    menuController: MenuController,
    onOAuthCallback: (String) -> Unit,
    onOpmlFile: (String) -> Unit,
    onReopen: () -> Unit,
    registry: AppEventRegistry? = awtAppEventRegistry(),
) {
    if (registry == null) return

    // Replace the JVM's default "About" panel (which shows "java" + the JVM version) with our
    // own About dialog, and route the native "Settings…" (⌘,) item to our Settings screen. Both
    // live in the macOS app (Keryx) menu, so they are omitted from AppMenuBar there. macOS-only in
    // practice (APP_ABOUT / APP_PREFERENCES are unsupported elsewhere).
    installDesktopHandler(registry, Desktop.Action.APP_ABOUT, "About menu") {
        it.onAbout { menuController.send(MenuCommand.About) }
    }
    installDesktopHandler(registry, Desktop.Action.APP_PREFERENCES, "Preferences menu") {
        it.onPreferences { menuController.send(MenuCommand.OpenSettings) }
    }

    // Install the in-process URI handler (macOS). Windows/Linux receive the URI as a
    // command-line argument instead, forwarded through SingleInstanceCoordinator.
    installDesktopHandler(registry, Desktop.Action.APP_OPEN_URI, "URI") {
        it.onOpenUri { uri ->
            val text = uri.toString()
            if (text.startsWith("$CUSTOM_URI_SCHEME://")) onOAuthCallback(text)
        }
    }

    // Install the in-process file-open handler (macOS). A double-clicked / "Open With"-launched
    // .opml file arrives here via an Apple Event whether or not Keryx was already running — unlike
    // Windows/Linux, which only ever deliver it as argv on a genuinely new process (see the
    // single-instance dispatch in main()).
    installDesktopHandler(registry, Desktop.Action.APP_OPEN_FILE, ".opml file-open") {
        it.onOpenFiles { files ->
            files
                .filter { file -> file.name.endsWith(".opml", ignoreCase = true) }
                .forEach { file -> onOpmlFile(file.absolutePath) }
        }
    }

    // macOS: a second Finder/Dock double-click while Keryx is already running never spawns a
    // second process - Launch Services enforces single-instance for a plain .app bundle itself -
    // it delivers a native "reopen" Apple Event to this process instead. SingleInstanceCoordinator's
    // file-lock/socket handoff in main() only ever sees a genuine second process (Windows/Linux), so
    // without this listener the window-restore + Dock-icon-reapply logic gated on activationRequests
    // (see onReopen) never runs on macOS, leaving the window hidden and the Dock showing a generic icon.
    installDesktopHandler(registry, Desktop.Action.APP_EVENT_REOPENED, "reopen") {
        it.onReopened { onReopen() }
    }
}
