package works.merc.keryx.app.appmenu

import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.AppMenuShortcut
import java.awt.KeyEventDispatcher
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * State + AWT plumbing for the in-window menu bar's visibility under the KDE Global Menu.
 *
 * When the Global Menu takes over, `AppMenuBarHost` stops rendering the Compose `MenuBar`. AWT's
 * native `MenuBar`/`MenuItem` accelerators only fire while the bar is attached to the frame, so
 * dropping the bar would silently break Ctrl+N/W/,/Q/R too — not just the visuals. The
 * [MenuShortcutDispatcher] here re-implements every shortcut at the `KeyboardFocusManager` level.
 *
 * To avoid double-firing (once via a native accelerator, once via this dispatcher) the two are kept
 * **mutually exclusive**: `AppMenuBarHost` installs the dispatcher exactly when the bar is hidden
 * and removes it the instant it returns — so at most one of the two is ever active (Decision 2).
 */

/** The AWT virtual-key code backing a [AppMenuShortcut]. Kept out of `AppMenuTree` so the model stays AWT-free. */
internal fun AppMenuShortcut.awtKeyCode(): Int = when (this) {
    AppMenuShortcut.AddFeed -> KeyEvent.VK_N
    AppMenuShortcut.CloseWindow -> KeyEvent.VK_W
    AppMenuShortcut.Settings -> KeyEvent.VK_COMMA
    AppMenuShortcut.Quit -> KeyEvent.VK_Q
    AppMenuShortcut.RefreshAll -> KeyEvent.VK_R
    AppMenuShortcut.ShowMenuBar -> KeyEvent.VK_M
    AppMenuShortcut.Search -> KeyEvent.VK_F
    AppMenuShortcut.ImportOpml -> KeyEvent.VK_I
    AppMenuShortcut.ExportOpml -> KeyEvent.VK_E
    AppMenuShortcut.UnreadOnly -> KeyEvent.VK_U
    AppMenuShortcut.ToggleRead -> KeyEvent.VK_U
    AppMenuShortcut.ToggleStar -> KeyEvent.VK_S
    AppMenuShortcut.OpenInBrowser -> KeyEvent.VK_O
    AppMenuShortcut.CopyUrl -> KeyEvent.VK_C
    AppMenuShortcut.FeedRefresh -> KeyEvent.VK_R
    AppMenuShortcut.FeedRename -> if (isMacOs) KeyEvent.VK_ENTER else KeyEvent.VK_F2
    AppMenuShortcut.FeedUnsubscribe -> KeyEvent.VK_DELETE
}

/** The `enabled` flag of an actionable node (`true` for anything without one). */
internal fun AppMenuNode.isEnabled(): Boolean = when (this) {
    is AppMenuNode.Item -> enabled
    is AppMenuNode.CheckboxItem -> enabled
    else -> true
}

/**
 * Finds the actionable node in [root] whose shortcut matches the given key + modifier combination,
 * or `null` if none does. Pure and AWT-key-code based, so it is unit-testable with synthetic input.
 *
 * A shortcut matches only on an exact modifier combination: its own [AppMenuShortcut.ctrl] /
 * [AppMenuShortcut.meta] / [AppMenuShortcut.shift] must equal [ctrl] / [meta] / [shift] — this is
 * what keeps e.g. Ctrl+R (`RefreshAll`) and Ctrl+Shift+R (`FeedRefresh`) from being confused with
 * one another. Enabled state is **not** consulted here — the caller decides whether to invoke.
 */
internal fun matchMenuShortcut(
    root: AppMenuRoot,
    keyCode: Int,
    ctrl: Boolean,
    meta: Boolean,
    shift: Boolean = false,
): AppMenuNode? {
    fun shortcutOf(node: AppMenuNode): AppMenuShortcut? = when (node) {
        is AppMenuNode.Item -> node.shortcut
        is AppMenuNode.CheckboxItem -> node.shortcut
        else -> null
    }

    fun search(nodes: List<AppMenuNode>): AppMenuNode? {
        for (node in nodes) {
            val shortcut = shortcutOf(node)
            if (shortcut != null &&
                shortcut.awtKeyCode() == keyCode &&
                shortcut.ctrl == ctrl &&
                shortcut.meta == meta &&
                shortcut.shift == shift
            ) {
                return node
            }
            if (node is AppMenuNode.Menu) {
                search(node.items)?.let { return it }
            }
        }
        return null
    }

    return search(root.menus)
}

/** Invokes an actionable node's action (a click, or a checkbox toggle). No-op for other nodes. */
internal fun AppMenuNode.invokeAction() {
    when (this) {
        is AppMenuNode.Item -> onClick()
        is AppMenuNode.CheckboxItem -> onCheckedChange(!checked)
        else -> Unit
    }
}

/**
 * A global key dispatcher that fires menu accelerators while the in-window bar is hidden. Reads the
 * **latest** tree via [currentTree] (rebuilt on every recomposition), so enabled/checked state is
 * always current.
 *
 * `KeyboardFocusManager` dispatchers see key events for every window in the process, but the native
 * accelerators this replaces only fire while the app's main frame itself has focus (not a `Dialog`).
 * [acceptsWindow] restores that scoping — the caller passes a predicate identifying the main frame so
 * shortcuts don't fire e.g. while the Settings or Add Feed dialog has focus.
 */
internal class MenuShortcutDispatcher(
    private val currentTree: () -> AppMenuRoot?,
    private val acceptsWindow: (Window?) -> Boolean = { true },
) : KeyEventDispatcher {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (!acceptsWindow(SwingUtilities.getWindowAncestor(event.component))) return false
        val tree = currentTree() ?: return false
        val modifiers = event.modifiersEx
        // Alt is never part of a shipped shortcut; bail so Alt combos reach the app normally.
        if (modifiers and KeyEvent.ALT_DOWN_MASK != 0) return false
        val ctrl = modifiers and KeyEvent.CTRL_DOWN_MASK != 0
        val meta = modifiers and KeyEvent.META_DOWN_MASK != 0
        val shift = modifiers and KeyEvent.SHIFT_DOWN_MASK != 0
        val node = matchMenuShortcut(tree, event.keyCode, ctrl, meta, shift) ?: return false
        // Consume the keystroke whether or not the item is enabled, so e.g. Ctrl+N never leaks as
        // typed text; only invoke when enabled (matching native disabled-accelerator behaviour).
        if (node.isEnabled()) node.invokeAction()
        return true
    }
}

/** Records an explicit in-window bar visibility preference (see [LocalSettings.appMenuBarVisible]). */
internal fun LocalSettings.withMenuBarVisible(visible: Boolean): LocalSettings =
    copy(appMenuBarVisible = visible)
