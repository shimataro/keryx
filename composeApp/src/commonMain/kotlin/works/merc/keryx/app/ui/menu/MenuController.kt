package works.merc.keryx.app.ui.menu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import works.merc.keryx.app.ui.navigation.Screen

/**
 * One-shot commands issued by the desktop application menu bar (see `AppMenuBar`) for actions whose
 * state lives inside a specific screen's composition (dialogs, navigation, focus) rather than in a
 * shared ViewModel. Collected by [works.merc.keryx.app.App], `HomeScreen`, and `FeedListPane`.
 */
enum class MenuCommand {
    AddFeed,
    AddFolder,
    AddTag,
    OpenSettings,
    FocusSearch,
    OpenInBrowser,
    CopyUrl,
    About,
}

/**
 * Bridges the menu bar (built outside the screen composition in `main.kt`) to the screens.
 *
 * - [currentScreen] is kept in sync by `App` so the menu bar can gate item enabled-state on the
 *   active top-level destination.
 * - [commands] carries one-shot menu clicks to whichever composable owns the target state.
 *
 * App-scoped Koin singleton (single-window desktop app).
 */
class MenuController {
    val currentScreen = MutableStateFlow<Screen>(Screen.Setup)

    private val _commands = MutableSharedFlow<MenuCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<MenuCommand> = _commands.asSharedFlow()

    fun send(command: MenuCommand) {
        _commands.tryEmit(command)
    }
}
