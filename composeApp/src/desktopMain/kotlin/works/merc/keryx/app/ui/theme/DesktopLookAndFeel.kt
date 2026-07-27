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

/** Whether [updateLookAndFeel] has applied a Look & Feel since the process started. */
private var appliedSinceStartup = false

/**
 * Whether [updateLookAndFeel] should go through with applying the Look & Feel.
 *
 * The first application after startup always goes through, even when [installLookAndFeel] already
 * installed this very theme. Installing a Look & Feel before the UI exists turns out not to be
 * enough on Linux: unless a `FlatLaf.updateUI()` pass runs once the components are up, they render
 * with Swing's cross-platform default instead. That was observable as "restarting on a fixed light
 * or dark theme looks like a 1990s Java app, but following the system theme looks right" — the
 * system case is simply the one where the startup guess (light) differed from the resolved theme,
 * so the update was not skipped and repaired everything on its way through.
 *
 * After that, only real theme changes are worth the work.
 */
internal fun shouldApplyLookAndFeel(
    installedDark: Boolean?,
    appliedSinceStartup: Boolean,
    dark: Boolean,
): Boolean = !appliedSinceStartup || installedDark != dark

/**
 * Keryx-specific FlatLaf defaults, applied on every setup. Only two keys are needed: FlatLaf
 * derives the rest from them — `@accentColor` drives menu-item selection
 * (`@selectionBackground`), checkmarks, focus rings and the default button, while `@background`
 * drives `MenuBar`/`PopupMenu` backgrounds (`@menuBackground`) and their derived border colors.
 *
 * Corner radii are deliberately not overridden: FlatLaf's own `Button.arc = 6` already matches
 * this app's `KeryxShapes.small`.
 */
internal fun keryxFlatLafDefaults(dark: Boolean): Map<String, String> = mapOf(
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
        installSystemLookAndFeel()
        logInstalledLookAndFeel()
        return
    }
    // setupFlatLaf reports failure by returning false, so this must be checked rather than
    // relying on an exception — FlatLaf.setup() catches its own and never throws.
    val installed = runCatching { setupFlatLaf(dark) }
        .onFailure { Log.warn(LOG_TAG, "Could not set up FlatLaf", it) }
        .getOrDefault(false)
    if (!installed) {
        Log.warn(LOG_TAG, "FlatLaf did not install; falling back to the system look and feel")
        installSystemLookAndFeel()
    }
    logInstalledLookAndFeel()
}

private fun installSystemLookAndFeel() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { Log.warn(LOG_TAG, "Could not set the system look and feel", it) }
}

/**
 * Records which Look & Feel is actually in force. Kept unconditional rather than behind a debug
 * flag: it only fires when the L&F changes, and a silent fall back to Swing's cross-platform
 * default is otherwise invisible — the app just quietly looks like a 1990s Java application.
 */
private fun logInstalledLookAndFeel() {
    val laf = UIManager.getLookAndFeel()
    Log.info(LOG_TAG, "Look and feel in use: ${laf?.name} (${laf?.javaClass?.name})")
}

/**
 * Applies the Look & Feel for the resolved theme and restyles every already-open window
 * (`FlatLaf.updateUI` walks `Window.getWindows()`, so the separate `DialogWindow`s this app uses
 * for dialogs are covered too).
 *
 * Called once when the window first composes and again on every in-app theme change. The first
 * call is what makes the UI actually render with FlatLaf rather than Swing's default — see
 * [shouldApplyLookAndFeel] — so it runs even when the theme has not changed.
 *
 * No-op off Linux, where the system L&F handles its own appearance. Must be called on the EDT.
 */
internal fun updateLookAndFeel(dark: Boolean) {
    if (!isLinux || !shouldApplyLookAndFeel(installedDark, appliedSinceStartup, dark)) return
    val updated = runCatching {
        setupFlatLaf(dark).also { if (it) FlatLaf.updateUI() }
    }
        .onFailure { Log.warn(LOG_TAG, "Could not update the look and feel for a theme change", it) }
        .getOrDefault(false)
    if (!updated) return
    appliedSinceStartup = true
    logInstalledLookAndFeel()
}

/**
 * @return whether FlatLaf actually became the installed Look & Feel. [installedDark] is only
 * recorded on success, so a failed attempt leaves [updateLookAndFeel] free to retry on the next
 * theme change rather than latching the app to whatever it fell back to.
 */
private fun setupFlatLaf(dark: Boolean): Boolean {
    // Extra defaults are only read while a look and feel is being set up, so they have to be
    // handed over before each setup() call rather than once at startup.
    FlatLaf.setGlobalExtraDefaults(keryxFlatLafDefaults(dark))
    // setup() swallows any failure and reports it by returning false, so the result carries the
    // only signal there is. FlatLaf's own explanation goes to java.util.logging, not here.
    val ok = if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
    if (ok) installedDark = dark
    return ok
}
