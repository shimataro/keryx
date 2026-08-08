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
 *   still has focus; this has no side effects, unlike U/S/O/C)
 * - U : toggle read/unread,  S : toggle star,  O : open in browser,  C : copy URL
 *   (U/S/O/C all act on the currently selected article; the caller is expected to scope these to
 *   the article list/detail panes since they have side effects — clipboard, browser launch,
 *   read/star state — that shouldn't fire while focus is elsewhere, e.g. the feed list)
 * - Every plain-letter shortcut above requires neither Ctrl nor Meta to be held, so it never
 *   shadows the OS's own Ctrl/Cmd+<letter> bindings (e.g. Ctrl+C for copy)
 * - Cmd/Ctrl+F : search
 * - Esc : abort an in-progress feed/folder drag (handled by [onEscape], which reports whether
 *   there was one — if not, the key is left alone for anything else to handle)
 *
 * When [searchFieldFocused] is true, all shortcuts are suppressed so the search text field (which
 * lives inside a pane, under this root `onPreviewKeyEvent`) receives typed letters/arrows normally.
 * Escape is the one exception: a drag can be in progress while the search field holds focus, and
 * aborting it must always be possible.
 */
fun Modifier.homeKeyboardShortcuts(
    searchFieldFocused: Boolean,
    onEscape: () -> Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onNextArticle: () -> Unit,
    onPreviousArticle: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onCopyUrl: () -> Unit,
    onSearch: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    if (event.key == Key.Escape) return@onPreviewKeyEvent onEscape()
    if (searchFieldFocused) return@onPreviewKeyEvent false
    when {
        (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.F -> { onSearch(); true }
        event.key == Key.DirectionDown -> { onDown(); true }
        event.key == Key.DirectionUp -> { onUp(); true }
        event.key == Key.DirectionLeft -> { onLeft(); true }
        event.key == Key.DirectionRight -> { onRight(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.J -> { onNextArticle(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.K -> { onPreviousArticle(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.U -> { onToggleRead(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.S -> { onToggleStar(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.O -> { onOpenInBrowser(); true }
        !event.isCtrlPressed && !event.isMetaPressed && event.key == Key.C -> { onCopyUrl(); true }
        else -> false
    }
}
