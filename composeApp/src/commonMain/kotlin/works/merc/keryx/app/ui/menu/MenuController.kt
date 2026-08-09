package works.merc.keryx.app.ui.menu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import works.merc.keryx.app.ui.home.HomePane
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
    RenameFeed,
    UnsubscribeFeed,
    About,
}

/**
 * Bridges the menu bar (built outside the screen composition in `main.kt`) to the screens.
 *
 * - [currentScreen] is kept in sync by `App` so the menu bar can gate item enabled-state on the
 *   active top-level destination.
 * - [focusedPane] is kept in sync by `HomeScreen` the same way, so the menu bar can gate
 *   feed-list-scoped items (Refresh/Tags/Move to folder/Rename/Unsubscribe) on which pane actually
 *   has keyboard focus, not just on there being a selection.
 * - [commands] carries one-shot menu clicks to whichever composable owns the target state.
 *
 * App-scoped Koin singleton (single-window desktop app).
 */
class MenuController {
    val currentScreen = MutableStateFlow<Screen>(Screen.Setup)
    val focusedPane = MutableStateFlow<HomePane?>(null)

    private val _commands = MutableSharedFlow<MenuCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<MenuCommand> = _commands.asSharedFlow()

    fun send(command: MenuCommand) {
        _commands.tryEmit(command)
    }
}
