package works.merc.keryx.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.isLinux
import javax.swing.UIManager

private const val LOG_TAG = "DesktopLookAndFeel"

/**
 * The Swing Look & Feel currently installed, or `null` before [installLookAndFeel] has run.
 * Guards [updateLookAndFeel] against re-running FlatLaf's setup (and the component-tree walk it
 * implies) when the resolved theme hasn't actually changed — [updateLookAndFeel] is driven from a
 * settings flow that also re-emits for unrelated theme-independent reasons.
 */
private var installedDark: Boolean? = null

/**
 * Keryx-specific FlatLaf defaults, applied on every setup. Only two keys are needed: FlatLaf
 * derives the rest from them — `@accentColor` drives menu-item selection
 * (`@selectionBackground`), checkmarks, focus rings and the default button, while `@background`
 * drives `MenuBar`/`PopupMenu` backgrounds (`@menuBackground`) and their derived border colors.
 *
 * Corner radii are deliberately not overridden: FlatLaf's own `Button.arc = 6` already matches
 * this app's `KeryxShapes.small`.
 */
private fun keryxFlatLafDefaults(dark: Boolean): Map<String, String> = mapOf(
    "@accentColor" to keryxAccentColor(dark).toHexString(),
    "@background" to keryxSurfaceColor(dark).toHexString(),
)

/** Formats a Compose [Color] as the `#rrggbb` string FlatLaf's properties parser expects. */
private fun Color.toHexString(): String = "#%06x".format(toArgb() and 0xFFFFFF)

/**
 * Installs the Swing Look & Feel used for the parts of the UI that are not drawn by Compose: the
 * application menu bar (a real `JMenuBar`), context menus, and the dialog button row.
 *
 * On Linux this is FlatLaf, tinted to this app's own theme — the platform's system L&F there is
 * Java's GTK2-era emulation, which looks dated next to a modern GTK/Qt desktop. macOS (Aqua) and
 * Windows already render these natively, so they keep the system L&F.
 *
 * Must be called before any Swing component is created.
 */
internal fun installLookAndFeel(dark: Boolean) {
    if (!isLinux) {
        // Without a system L&F, javax.swing.JButton (KeryxAlertDialog's native button row)
        // renders with Swing's generic cross-platform look instead of the OS-native one.
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            .onFailure { Log.warn(LOG_TAG, "Could not set the system look and feel", it) }
        return
    }
    runCatching { setupFlatLaf(dark) }
        .onFailure {
            Log.warn(LOG_TAG, "Could not set up FlatLaf; falling back to the system look and feel", it)
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
                .onFailure { fallback -> Log.warn(LOG_TAG, "Could not set the system look and feel", fallback) }
        }
}

/**
 * Switches the installed Look & Feel between light and dark to follow an in-app theme change, and
 * restyles every already-open window (`FlatLaf.updateUI` walks `Window.getWindows()`, so the
 * separate `DialogWindow`s this app uses for dialogs are covered too).
 *
 * No-op off Linux, where the system L&F handles its own appearance. Must be called on the EDT.
 */
internal fun updateLookAndFeel(dark: Boolean) {
    if (!isLinux || installedDark == dark) return
    runCatching {
        setupFlatLaf(dark)
        FlatLaf.updateUI()
    }.onFailure { Log.warn(LOG_TAG, "Could not update the look and feel for a theme change", it) }
}

private fun setupFlatLaf(dark: Boolean) {
    // Extra defaults are only read while a look and feel is being set up, so they have to be
    // handed over before each setup() call rather than once at startup.
    FlatLaf.setGlobalExtraDefaults(keryxFlatLafDefaults(dark))
    if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    installedDark = dark
}
