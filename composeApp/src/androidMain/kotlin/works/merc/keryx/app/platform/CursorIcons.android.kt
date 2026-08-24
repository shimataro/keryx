package works.merc.keryx.app.platform

import androidx.compose.ui.input.pointer.PointerIcon

/** Android has no mouse-driven resize dividers, so this is never actually shown. */
actual object CursorIcons {
    actual val horizontalResize: PointerIcon = PointerIcon.Default
}
