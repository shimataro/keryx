package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A single entry of a native context menu shown via [nativeContextMenu]. */
sealed interface NativeMenuEntry {
    val label: String
}

/** A leaf item of a native context menu that performs [onClick] when selected. */
data class NativeMenuItem(override val label: String, val onClick: () -> Unit) : NativeMenuEntry

/** A native context menu entry that expands into a nested submenu of [items]. */
data class NativeSubMenu(override val label: String, val items: List<NativeMenuItem>) : NativeMenuEntry

/**
 * Attaches a real OS-native context menu (not a Compose-drawn popup) with the
 * given [items] to this element, triggered by a right-click (on mobile
 * targets this would become long-press instead). The number of top-level
 * `items` — and the number of children of any [NativeSubMenu] among them —
 * is expected to be stable for a given call site across ordinary
 * recompositions (menus don't grow/shrink on every recomposition); it may
 * still change when the underlying data it reflects changes (e.g. the user
 * adds a folder), which is handled by rebuilding the native menu.
 *
 * [onOpen] is invoked right before the menu is shown; callers typically use it
 * to select the right-clicked row, so right-click behaves like left-click
 * selection. If [items] is empty, no menu is shown and [onOpen] is the only
 * effect — useful for a pane background where a right-click should just move
 * focus without selecting anything.
 */
@Composable
expect fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit = {},
): Modifier
