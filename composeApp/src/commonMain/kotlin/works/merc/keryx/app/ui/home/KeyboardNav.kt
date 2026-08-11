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
 * When [textInputFocused] is true, all shortcuts are suppressed so the focused text field (the
 * sidebar search field, or a feed-list row's inline name editor — both live inside a pane, under
 * this root `onPreviewKeyEvent`) receives typed letters/arrows normally.
 * Escape is the one exception: a drag can be in progress while the search field holds focus, and
 * aborting it must always be possible.
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
    isMacOs: Boolean = works.merc.keryx.app.platform.isMacOs,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key == Key.Escape) return@onPreviewKeyEvent onEscape()
    if (textInputFocused) return@onPreviewKeyEvent false
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
