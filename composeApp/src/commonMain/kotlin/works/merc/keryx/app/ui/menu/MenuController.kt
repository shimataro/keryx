package works.merc.keryx.app.ui.menu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import works.merc.keryx.app.ui.navigation.Screen

/**
 * One-shot commands for actions whose state lives inside a specific screen's composition
 * (dialogs, navigation, focus) rather than in a shared ViewModel. Issued by the desktop
 * application menu bar (see `AppMenuBar`) and, on Android — which has no menu bar — by in-app
 * controls that reach the same actions directly (`GeneralTab`'s About row, `FeedListPane`'s
 * Settings row). Collected by [works.merc.keryx.app.App], `HomeScreen`, and `FeedListPane`.
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
    CopyFeedUrl,
    CopySiteUrl,
    About,
}

/**
 * Bridges the menu bar (built outside the screen composition in `main.kt`) to the screens.
 *
 * - [currentScreen] is kept in sync by `App` so the menu bar can gate item enabled-state on the
 *   active top-level destination.
 * - [feedListKeysActive] is kept in sync by `HomeScreen` (`feedListItemKeysActive`: the feed list
 *   holds keyboard focus and no text field does), so the Feed menu attaches its bare F2/Return and
 *   Delete accelerators only while those keys would really act — a native Swing accelerator
 *   (unlike `KeyboardNav.kt`) has no way to defer to a focused text field or another pane. The
 *   items stay enabled either way.
 * - [commands] carries one-shot menu clicks to whichever composable owns the target state.
 *
 * App-scoped Koin singleton.
 */
class MenuController {
    val currentScreen = MutableStateFlow<Screen>(Screen.Setup)
    val feedListKeysActive = MutableStateFlow(false)

    private val _commands = MutableSharedFlow<MenuCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<MenuCommand> = _commands.asSharedFlow()

    fun send(command: MenuCommand) {
        _commands.tryEmit(command)
    }
}
