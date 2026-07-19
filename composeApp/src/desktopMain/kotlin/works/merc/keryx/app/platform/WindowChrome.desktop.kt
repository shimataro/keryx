package works.merc.keryx.app.platform

import androidx.compose.runtime.mutableStateOf

actual object WindowChrome {
    private val state = mutableStateOf(0f)
    actual var titleBarInsetDp: Float
        get() = state.value
        set(value) { state.value = value }
}
