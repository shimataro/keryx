package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import java.awt.Desktop
import java.awt.Frame
import java.awt.Image
import java.awt.Taskbar

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
