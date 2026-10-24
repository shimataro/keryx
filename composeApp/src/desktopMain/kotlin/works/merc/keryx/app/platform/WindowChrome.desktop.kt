package works.merc.keryx.app.platform

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.Window
import javax.swing.JRootPane
import javax.swing.RootPaneContainer

actual object WindowChrome {
    private val state = mutableStateOf(0f)
    actual var titleBarInsetDp: Float
        get() = state.value
        set(value) { state.value = value }
}

/**
 * Merges a macOS window's title bar into its content (full-size content view, transparent title
 * bar, no title text) — shared by the main window and every dialog window.
 */
internal fun JRootPane.applyMacMergedTitleBar() {
    putClientProperty("apple.awt.fullWindowContent", true)
    putClientProperty("apple.awt.transparentTitleBar", true)
    putClientProperty("apple.awt.windowTitleVisible", false)
}

/**
 * Pre-fills the native window and its content pane with [color], so the platform-default
 * background never flashes before Compose paints its first frame.
 */
internal fun Window.paintNativeSurface(color: Color) {
    val nativeSurface = java.awt.Color(color.toArgb())
    background = nativeSurface
    (this as? RootPaneContainer)?.contentPane?.background = nativeSurface
}
