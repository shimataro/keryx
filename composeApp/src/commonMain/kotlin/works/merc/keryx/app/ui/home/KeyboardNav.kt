package works.merc.keryx.app.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Which kind of text input currently holds focus inside `FeedListPane`/`ArticleListPane` — reported
 * through each pane's own `onTextInputFocusChange(HomeTextInput?)`, `null` meaning neither. Distinct
 * from the plain suppression `textInputFocused` (below) still needs at the shortcut-dispatch level:
 * `HomeScreen` uses this finer-grained value to decide what ↓/↑ should do while typing (see
 * `KeyboardNav.kt`'s own KDoc on why ↓/↑ alone pass through the [homeKeyboardShortcuts] guard) —
 * [SearchField] descends into the results list, [RowNameEditor] is left alone entirely so the
 * caret stays put and the editor doesn't accidentally commit. Before this type existed, both cases
 * were folded into one `Boolean`, which is exactly why ↓/↑ reaching an inline row editor used to
 * both move the keyboard-navigated feed selection *and* leave the editor open with nothing to show
 * for the keypress — one signal, two unrelated fields, and no way to tell them apart at the point
 * that needed to.
 */
enum class HomeTextInput { SearchField, RowNameEditor }

/**
 * Keyboard shortcuts for the home screen (attach to a focused root):
 * - ↓ / ↑ / ← / → : pane-dependent navigation (selection change, scroll, or focus move —
 *   the caller decides based on which pane is logically focused)
 * - J : next article,  K : previous article (always operate on the article list, regardless of
 *   which pane is focused — deliberately unscoped so articles can be skimmed while the feed list
 *   still has focus; this has no side effects)
 * - F2 (Windows/Linux) or Return (macOS) : rename/edit the selected item,  Delete or Backspace :
 *   unsubscribe/delete the selected item (mirrors each OS's own file-manager rename convention —
 *   Explorer/Nautilus/Dolphin use F2, Finder uses Return). The caller is expected to scope these
 *   to the feed list pane.
 * - Toggle read/unread, toggle star, open in browser, copy URL, and refresh-selected-feed have no
 *   bare-key binding here — they are Ctrl+Shift+<letter> app-menu accelerators instead (see
 *   `AppMenuShortcut`), since those actions have side effects (clipboard, browser launch,
 *   read/star state, network) that shouldn't fire from an easily-mistyped bare key.
 * - J / K / F2 / Return / Delete / Backspace all require neither Ctrl nor Meta to be held, so they
 *   never shadow the OS's own Ctrl/Cmd+<key> bindings
 * - Cmd/Ctrl+F : search
 * - Esc : abort an in-progress feed/folder drag (handled by [onEscape], which reports whether
 *   there was one — if not, the key is left alone for anything else to handle)
 *
 * When [textInputFocused] is true, most shortcuts are suppressed so the focused text field (the
 * sidebar search field, or a feed-list row's inline name editor — both live inside a pane, under
 * this root `onPreviewKeyEvent`) receives typed letters/arrows normally. Two exceptions:
 * - Escape: a drag can be in progress while the search field holds focus, and aborting it must
 *   always be possible.
 * - ↓ / ↑: still call [onDown]/[onUp] even while the *search* field holds focus (`HomeScreen`
 *   routes a row's own inline name editor back to a no-op instead — see its own `HomeTextInput`
 *   dispatch). A single-line field has no caret use for either key, so this is what lets the
 *   result list be browsed by keyboard without leaving the field first — descending into the list
 *   is `HomeScreen`'s job once it sees the key reach here; this function's only job is to let ↓/↑
 *   reach it in the first place. Returning `true` here also means the key event is fully consumed
 *   at this root `onPreviewKeyEvent`, before it can reach whatever a descendant (the field itself,
 *   a nearby row, or the host platform) would otherwise do with an unconsumed arrow key — which
 *   matters concretely on Android, where an unhandled D-pad/arrow key can fall through to the
 *   platform's own View-level focus search. ← / → and every letter key stay fully suppressed here —
 *   neither has a role once the result list is reachable via ↓/↑ alone, so there is no reason to
 *   grow a second special case for them.
 *
 * [onKeyboardEngaged] fires on the first `KeyDown` that reaches past the [textInputFocused] guard —
 * i.e. a genuine physical-key press, not a soft-keyboard key routed to a focused text field (those
 * never reach this point). `HomeScreen` latches this into [LocalKeyboardEngaged] to gate Android's
 * keyboard-focus ring (see `ListRowChrome.kt`'s `PaneFocusIndication.Ring`): a touch-only
 * session should never show a focus indicator meant for keyboard navigation.
 */
fun Modifier.homeKeyboardShortcuts(
    textInputFocused: Boolean,
    onEscape: () -> Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onNextArticle: () -> Unit,
    onPreviousArticle: () -> Unit,
    onFeedListRename: () -> Unit,
    onFeedListDelete: () -> Unit,
    onSearch: () -> Unit,
    onKeyboardEngaged: () -> Unit = {},
    isMacOs: Boolean = works.merc.keryx.app.platform.isMacOs,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key == Key.Escape) return@onPreviewKeyEvent onEscape()
    if (textInputFocused) {
        // Only ↓/↑ pass through here — see this function's own KDoc for why. A real hardware key
        // still reached this handler either way, so it still latches onKeyboardEngaged.
        return@onPreviewKeyEvent when (event.key) {
            Key.DirectionDown -> { onKeyboardEngaged(); onDown(); true }
            Key.DirectionUp -> { onKeyboardEngaged(); onUp(); true }
            else -> false
        }
    }
    onKeyboardEngaged()
    when {
        (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.F -> { onSearch(); true }
        event.key == Key.DirectionDown -> { onDown(); true }
        event.key == Key.DirectionUp -> { onUp(); true }
        event.key == Key.DirectionLeft -> { onLeft(); true }
        event.key == Key.DirectionRight -> { onRight(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.J -> { onNextArticle(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.K -> { onPreviousArticle(); true }
        !event.isCtrlPressed && !event.isMetaPressed &&
            (if (isMacOs) event.key == Key.Enter else event.key == Key.F2) -> { onFeedListRename(); true }
        !event.isCtrlPressed && !event.isMetaPressed &&
            (event.key == Key.Delete || event.key == Key.Backspace) -> { onFeedListDelete(); true }
        else -> false
    }
}
