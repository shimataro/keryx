package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key

/** A single entry of a native context menu shown via [nativeContextMenu]. */
sealed interface NativeMenuEntry {
    val label: String
}

/** An entry that performs an action rather than opening a submenu. */
sealed interface NativeMenuLeaf : NativeMenuEntry {
    val onClick: () -> Unit
}

/**
 * A shortcut-key hint rendered alongside a [NativeMenuItem], right-aligned in the platform's own
 * native style (never baked into the label as parenthetical text). [ctrl] means "use the
 * platform's primary modifier" (Ctrl elsewhere, ⌘ on macOS) — same convention as
 * `AppMenuShortcut.ctrl` in `ui/menu/AppMenuTree.kt`, kept as a separate, smaller type here since
 * that one is desktopMain-only and this needs to be constructible from commonMain call sites.
 *
 * A bare key (`ctrl = false`) — used for the rename (F2/Return) and delete (Delete) family of
 * items — can only be shown natively on Linux (`SwingPopupHandle`, via `JMenuItem.accelerator`):
 * AWT's `java.awt.MenuShortcut` (macOS/Windows' `AwtPopupHandle`) has no way to represent a
 * shortcut without the primary modifier, so those items render with no hint at all there. A
 * modifier'd key (`ctrl = true`) renders on every platform.
 */
data class NativeMenuShortcut(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false)

/** A leaf item of a native context menu that performs [onClick] when selected. */
data class NativeMenuItem(
    override val label: String,
    val shortcut: NativeMenuShortcut? = null,
    val enabled: Boolean = true,
    override val onClick: () -> Unit,
) : NativeMenuLeaf

/**
 * A leaf item that also carries an on/off state, drawn with the platform's own checkmark. Use
 * this — rather than marking the [label] itself — whenever an item reflects a state the user
 * toggles, so the menu matches how every other app on the platform shows it.
 */
data class NativeCheckMenuItem(
    override val label: String,
    val checked: Boolean,
    override val onClick: () -> Unit,
) : NativeMenuLeaf

/** A native context menu entry that expands into a nested submenu of [items]. */
data class NativeSubMenu(override val label: String, val items: List<NativeMenuLeaf>) : NativeMenuEntry

/** A visual separator between groups of items in a native context menu. */
data object NativeMenuSeparator : NativeMenuEntry {
    override val label: String = ""
}

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
 * [items] is only evaluated when a right-click actually happens — the native
 * widgets are built lazily on that first click, never on composition. Adding
 * this modifier to a `LazyColumn` row therefore costs nothing until the user
 * opens the menu on that row.
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
