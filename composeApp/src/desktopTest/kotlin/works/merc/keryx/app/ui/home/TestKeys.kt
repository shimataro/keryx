package works.merc.keryx.app.ui.home

import androidx.compose.ui.input.key.Key
import works.merc.keryx.app.platform.isMacOs

/** Ctrl+C everywhere except macOS, which copies with Cmd+C instead (`isCopyKeyEvent`). */
internal val copyModifierKey: Key
    get() = if (isMacOs) Key.MetaLeft else Key.CtrlLeft
