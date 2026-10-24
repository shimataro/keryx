package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is in front of the user right now — reported by each UI, read by shared code that
 * behaves differently while the user is looking at the app (see [RefreshCycleRunner]'s
 * new-article notification).
 *
 * "In front" is, per platform: the main window has focus (desktop, Compose); the main window is the
 * key window (macOS); the main Activity is resumed (Android); the scene phase is `active` (iOS). A
 * settings window in front of the main window counts as not in front, since the main window is then
 * not the one with focus. Each platform only reports the state through [set]; nothing here decides it.
 *
 * Starts as `false` (not in front), so a process with no UI at all — Android's `WorkManager` wakeup
 * or an iOS background task — reads as not in front.
 */
class AppForeground {
    private val state = MutableStateFlow(false)

    /** Whether the app is currently in front. */
    val inForeground: StateFlow<Boolean> = state.asStateFlow()

    /** Reports whether the app is now in front. */
    fun set(inForeground: Boolean) {
        state.value = inForeground
    }
}
