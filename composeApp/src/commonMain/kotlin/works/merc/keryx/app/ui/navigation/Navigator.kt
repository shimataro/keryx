package works.merc.keryx.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Top-level destinations. Article view is a pane within [Screen.Home], not a route; Settings is a
 * modeless dialog window ([works.merc.keryx.app.ui.settings.SettingsDialog]) shown over Home, not a
 * route either.
 */
sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
}

/** Minimal stack-based navigator for the desktop single-window app. */
class Navigator(start: Screen) {
    private val stack = mutableStateListOf(start)

    val current: Screen get() = stack.last()

    fun navigate(screen: Screen) {
        stack.add(screen)
    }

    fun replace(screen: Screen) {
        stack[stack.lastIndex] = screen
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

@Composable
fun rememberNavigator(start: Screen): Navigator = remember { Navigator(start) }
