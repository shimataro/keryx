package works.merc.keryx.app.platform

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_copy
import java.awt.Component
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.SwingUtilities

/**
 * The menu entries for a text selection, taken from the selection's own actions.
 *
 * Only [TextContextMenu.TextManager.copy] is ever non-null behind a `SelectionContainer` — its
 * text is read-only, so there is nothing to cut or paste and no select-all — which is why this
 * deliberately maps that one action rather than the full cut/copy/paste/select-all set: an entry
 * whose label could never be shown would still need a translated string in both locales.
 *
 * A copy action with nothing selected is disabled rather than dropped, so the menu keeps the same
 * single row either way. That matches both Compose's own default text menu ([TextContextMenu.Default]
 * shows disabled items) and the rest of this app's native menus, which grey an unavailable action
 * out instead of hiding it.
 *
 * @param textManager The selection whose actions the menu is built from.
 * @param copyLabel The localized label for the copy action.
 * @return The entries to build the native menu widgets from.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun textSelectionMenuEntries(
    textManager: TextContextMenu.TextManager,
    copyLabel: String,
): List<NativeMenuEntry> = listOfNotNull(
    textManager.copy?.let { NativeMenuItem(label = copyLabel, enabled = it.enabled, onClick = it.execute) },
)

/**
 * Where the pointer is, in [owner]'s own coordinate space.
 *
 * Read from the pointer rather than from `ContextMenuState.Status.Open`'s own rect: that rect is in
 * the Compose area's local, density-scaled coordinate space, while a [NativePopupHandle] positions
 * against an AWT component in Java user space. Compose's own `JPopupContextMenuRepresentation`
 * resolves the position exactly this way, and [nativeContextMenu] passes user-space coordinates
 * too, so all the native menus in the app land where the click was.
 *
 * @param owner The component to resolve the pointer position against.
 * @return The pointer position in [owner]'s coordinates, or null when there is no pointer to read.
 */
private fun pointerPositionIn(owner: Component): Point? {
    val onScreen = MouseInfo.getPointerInfo()?.location ?: return null
    return Point(onScreen).also { SwingUtilities.convertPointFromScreen(it, owner) }
}

/**
 * Draws a text selection's context menu with the same native widgets every row context menu uses:
 * [AwtPopupHandle] on macOS, [SwingPopupHandle] elsewhere, picked by the one [defaultPopupHandle].
 *
 * This stands in for Compose Foundation's own `JPopupContextMenuRepresentation` (and for
 * `JPopupTextMenu`, which wraps it) because both are hard-typed to `javax.swing.JPopupMenu` and so
 * can never reach macOS's `java.awt.PopupMenu` — the genuine `NSMenu` a feed- or article-row menu
 * gets there. Everything above the widget stays Compose's: its `TextContextMenuArea` detects the
 * right-click, selects the word under the cursor on macOS, and owns the [ContextMenuState] this
 * reacts to.
 *
 * @param window The window the AWT backend attaches its menu to; null outside a real window.
 * @param owner The component the menu is positioned against — the window's content pane in
 * production, the very component [nativeContextMenu] uses as its invoker.
 * @param entries The entries for the current selection, read afresh on every open.
 * @param factory Builds the backend. Defaulted to the platform choice; a parameter only so a test
 * can observe what is handed to it.
 */
internal class NativeTextSelectionMenuRepresentation(
    window: NativeWindowHandle?,
    private val owner: Component,
    private val entries: () -> List<NativeMenuEntry>,
    factory: NativePopupFactory = DefaultPopupFactory,
) : ContextMenuRepresentation {

    // Built on the first open and kept for as long as the selection's area is composed, rather than
    // rebuilt per open the way Compose's own representation does. The AWT backend's menu has to
    // stay in the window's hierarchy while it is on screen — detaching it destroys its native peer
    // — and nothing here can tell when that menu has been dismissed (see Representation). One menu
    // per call site, relabelled from the current selection before each show, sidesteps that
    // entirely; [dispose] is what finally releases it.
    private val popup = LazyNativePopup(window = window, factory = factory)

    /**
     * Shows the native menu whenever [state] says the selection's menu has been opened.
     *
     * @param state The menu state Compose's `TextContextMenuArea` opens.
     * @param items Ignored. It only ever carries items a surrounding `ContextMenuDataProvider`
     * contributed, and this app adds none; the entries come from the selection's own `TextManager`
     * (see [textSelectionMenuEntries]) instead. `JPopupTextMenu` hands them over the same way.
     */
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        if (state.status !is ContextMenuState.Status.Open) return
        DisposableEffect(Unit) {
            pointerPositionIn(owner)?.let { showAt(it.x, it.y) }
            // Closed the moment the menu has been handed to the toolkit, not when it is actually
            // dismissed. Neither backend's menu is drawn by Compose — each is a real, separate
            // window that dismisses itself — so nothing reads this state except the decision to
            // *open* a menu, and leaving it Open would swallow the next right-click. Compose's own
            // JPopupContextMenuRepresentation can wait for PopupMenuListener's
            // popupMenuWillBecomeInvisible because it only ever builds a Swing menu;
            // java.awt.PopupMenu has no equivalent hook at all, and closing at a different moment
            // per platform is exactly the divergence this class exists to remove.
            state.status = ContextMenuState.Status.Closed
            // Deliberately empty: this effect is disposed by the very state write above, so hiding
            // or detaching anything here would tear down the menu that was just shown.
            onDispose {}
        }
    }

    /**
     * Shows the menu for the current selection.
     *
     * Split out of [Representation] so the widget side can be exercised without a real window:
     * deciding *when* to show is Compose's, and everything below this is [LazyNativePopup]'s.
     *
     * @param x The horizontal display coordinate, in [owner]'s space.
     * @param y The vertical display coordinate, in [owner]'s space.
     */
    internal fun showAt(x: Int, y: Int) {
        val current = entries()
        // Nothing to show rather than an empty box — the same guard [nativeContextMenu] applies,
        // and what Compose's own DefaultContextMenuRepresentation does with no components. Not
        // reachable today: a `SelectionContainer` always offers a copy action.
        if (current.isEmpty()) return
        popup.showFor(current, owner, x, y)
    }

    /** Releases the native widgets. Called when the selection's area leaves the composition. */
    fun dispose() {
        popup.dispose()
    }
}

/**
 * The [TextContextMenu] a selection is given on desktop: the same native menu [nativeContextMenu]
 * builds for a feed or article row, on every platform — see [NativeTextSelectionMenuRepresentation].
 *
 * @param window The window the menu belongs to.
 * @param copyLabel The localized label for the copy action.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class NativeTextContextMenu(
    private val window: NativeWindowHandle,
    private val copyLabel: String,
) : TextContextMenu {

    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val representation = remember(window, textManager, copyLabel) {
            NativeTextSelectionMenuRepresentation(
                window = window,
                owner = window.contentPane,
                entries = { textSelectionMenuEntries(textManager, copyLabel) },
            )
        }
        DisposableEffect(representation) { onDispose { representation.dispose() } }
        CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
            TextContextMenuArea(
                textManager = textManager,
                // Empty by design — see NativeTextSelectionMenuRepresentation.Representation.
                items = { emptyList() },
                state = state,
                content = content,
            )
        }
    }
}

/**
 * Desktop `actual`: gives a text selection the app's own native context menu instead of Compose
 * Foundation's Compose-drawn popup, down to the same widget class as a row menu's on every
 * platform — `java.awt.PopupMenu` (a real `NSMenu`) on macOS, `javax.swing.JPopupMenu` on Windows
 * and Linux.
 *
 * Compose keeps everything but the widget: the items come from the selection's own actions, and
 * the right-click detection (including macOS's select-the-word-under-the-cursor behavior) stays
 * with its `TextContextMenuArea`, so nothing here competes with the selection gestures around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun NativeTextSelectionContextMenu(content: @Composable () -> Unit) {
    val window = LocalNativeWindow.current
    val copyLabel = stringResource(Res.string.common_copy)
    val textContextMenu = remember(window, copyLabel) {
        // With no window there is nothing to attach or position a native menu against (a
        // `ComposePanel` test host, or composition before the window exists), so fall back to
        // Compose's own menu rather than leaving the selection with no menu at all.
        if (window == null) TextContextMenu.Default else NativeTextContextMenu(window, copyLabel)
    }
    CompositionLocalProvider(LocalTextContextMenu provides textContextMenu, content = content)
}
