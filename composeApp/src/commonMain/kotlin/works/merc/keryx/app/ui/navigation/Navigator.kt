package works.merc.keryx.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Top-level destinations. Article view is a pane within [Screen.Home], not a route; Settings is a
 * modeless dialog window ([works.merc.keryx.app.ui.settings.SettingsDialog]) shown over Home, not a
 * route either.
 */
sealed interface Screen {
    data object Setup : Screen
    data object Home : Screen
}

/** Tracks the single current top-level [Screen] for the app's whole lifetime. */
class Navigator(start: Screen) {
    var current: Screen by mutableStateOf(start)
        private set

    fun replace(screen: Screen) {
        current = screen
    }
}

@Composable
fun rememberNavigator(start: Screen): Navigator = remember { Navigator(start) }
