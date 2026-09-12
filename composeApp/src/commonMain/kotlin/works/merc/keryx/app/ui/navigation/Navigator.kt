package works.merc.keryx.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Top-level destinations. Article view is a pane within [Screen.Home], not a route; Settings
 * ([works.merc.keryx.app.ui.settings.SettingsDialog]) is a dialog shown over Home, not a route
 * either — a modeless window on desktop, a modal near-fullscreen `Dialog` on Android (see that
 * composable's own KDoc for both).
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
