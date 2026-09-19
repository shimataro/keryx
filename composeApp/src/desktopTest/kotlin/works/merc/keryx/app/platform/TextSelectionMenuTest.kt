package works.merc.keryx.app.platform

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.JMenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers what [NativeTextSelectionContextMenu] hands to Compose: the selection's own actions
 * converted into the very widgets `nativeContextMenu` builds, so a right-click on selected text
 * opens the same kind of menu as a right-click on a feed or article row — including on macOS,
 * where that means a `java.awt.PopupMenu` rather than a Swing one.
 *
 * Showing the menu for real is not covered — that needs a display, and there is no
 * [NativeWindowHandle] to hang one off in a test host anyway (the same limitation
 * [LazyNativePopupTest] documents). What the conversion and the backend choice produce is exactly
 * the part that can go wrong silently: an empty menu, an item dropped instead of greyed out, or a
 * menu drawn with the wrong toolkit all still "open" without error.
 */
@OptIn(ExperimentalFoundationApi::class)
class TextSelectionMenuTest {

    /**
     * Stands in for the `TextManager` a `SelectionContainer` exposes: read-only text, so only
     * [copy] is ever non-null there.
     */
    private class FakeTextManager(
        override val copy: TextContextMenu.Action?,
        override val selectedText: AnnotatedString = AnnotatedString("selected"),
    ) : TextContextMenu.TextManager {
        override val cut: TextContextMenu.Action? = null
        override val paste: TextContextMenu.Action? = null
        override val selectAll: TextContextMenu.Action? = null
        override fun selectWordAtPositionIfNotAlreadySelected(offset: Offset) = Unit
    }

    /** Records what the representation asked the backend to build, sync and show. */
    private class FakeHandle(val builtFrom: List<NativeMenuEntry>) : NativePopupHandle {
        val synced = mutableListOf<List<NativeMenuEntry>>()
        val shownAt = mutableListOf<Pair<Int, Int>>()
        var detachCount = 0

        override fun sync(items: List<NativeMenuEntry>) {
            synced += items
        }

        override fun attach(window: NativeWindowHandle?) = Unit

        override fun detach(window: NativeWindowHandle?) {
            detachCount++
        }

        override fun show(invoker: Component, x: Int, y: Int) {
            shownAt += x to y
        }
    }

    // A peer-free Component rather than a real widget: nothing here needs one, and constructing an
    // AWT widget would drag in a display. Mirrors LazyNativePopupTest's own invoker.
    private val invoker = object : Component() {}

    private fun managerWithCopy(enabled: Boolean, execute: () -> Unit = {}) =
        FakeTextManager(TextContextMenu.Action(enabled = enabled, execute = execute))

    /**
     * Builds the backend exactly as the production default does, with the platform pinned.
     *
     * @param textManager The selection whose actions the menu is built from.
     * @param macOs Whether to build the macOS backend.
     * @return The backend, already relabelled the way [LazyNativePopup] relabels it before a show.
     */
    private fun backendFor(textManager: TextContextMenu.TextManager, macOs: Boolean): NativePopupHandle {
        val entries = textSelectionMenuEntries(textManager, copyLabel = "コピー")
        return defaultPopupHandle(entries, { entries }, macOs = macOs).also { it.sync(entries) }
    }

    private fun representationOver(
        entries: () -> List<NativeMenuEntry>,
        built: MutableList<FakeHandle>,
    ) = NativeTextSelectionMenuRepresentation(
        window = null,
        owner = invoker,
        entries = entries,
        factory = { items, _ -> FakeHandle(items).also { built += it } },
    )

    @Test
    fun theEntryCarriesTheLabelAndEnabledStateOfTheCopyAction() {
        val entries = textSelectionMenuEntries(managerWithCopy(enabled = false), copyLabel = "コピー")

        assertEquals(listOf(LeafSignature("コピー", checked = null, enabled = false)), menuSignature(entries))
    }

    @Test
    fun aTextManagerWithNoCopyActionProducesNoEntries() {
        // Defensive: only a `SelectionContainer` reaches this today, and its copy action is always
        // present. An empty menu must still build rather than throw.
        val entries = textSelectionMenuEntries(FakeTextManager(copy = null), copyLabel = "コピー")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun clickingCopyRunsTheSelectionsOwnCopyAction() {
        var copied = false
        val entries = textSelectionMenuEntries(managerWithCopy(enabled = true) { copied = true }, copyLabel = "コピー")

        assertIs<NativeMenuLeaf>(entries.single()).onClick()

        assertTrue(copied)
    }

    /**
     * The point of the whole exercise: macOS draws the selection's menu with the same
     * `java.awt.PopupMenu` — a genuine `NSMenu` — that a feed or article row's menu gets there,
     * rather than the `javax.swing.JPopupMenu` Compose's own `JPopupTextMenu` is hard-typed to.
     * `macOs` is pinned so this holds on any host.
     */
    @Test
    fun macOsBuildsTheSelectionsMenuWithTheSameAwtWidgetAsARowMenu() {
        val handle = assertIs<AwtPopupHandle>(backendFor(managerWithCopy(enabled = true), macOs = true))

        assertEquals(1, handle.popupMenu.itemCount)
        assertEquals("コピー", handle.popupMenu.getItem(0).label)
        assertTrue(handle.popupMenu.getItem(0).isEnabled)
    }

    @Test
    fun windowsAndLinuxBuildTheSelectionsMenuWithTheSameSwingWidgetAsARowMenu() {
        val handle = assertIs<SwingPopupHandle>(backendFor(managerWithCopy(enabled = true), macOs = false))

        assertEquals(1, handle.popupMenu.componentCount)
        val item = assertIs<JMenuItem>(handle.popupMenu.getComponent(0))
        assertEquals("コピー", item.text)
        assertTrue(item.isEnabled)
        // Same as every other native menu in the app: a lightweight popup would be drawn behind
        // the article reader's native WebView.
        assertFalse(handle.popupMenu.isLightWeightPopupEnabled)
    }

    @Test
    fun anEmptySelectionKeepsTheItemButGreysItOutOnBothBackends() {
        // Right-clicking with nothing selected must still open a menu — the app's other native
        // menus disable an unavailable action rather than hiding it, and so does Compose's own
        // default text menu.
        val awt = assertIs<AwtPopupHandle>(backendFor(managerWithCopy(enabled = false), macOs = true))
        val swing = assertIs<SwingPopupHandle>(backendFor(managerWithCopy(enabled = false), macOs = false))

        assertEquals(1, awt.popupMenu.itemCount)
        assertFalse(awt.popupMenu.getItem(0).isEnabled)
        assertEquals(1, swing.popupMenu.componentCount)
        assertFalse(assertIs<JMenuItem>(swing.popupMenu.getComponent(0)).isEnabled)
    }

    @Test
    fun selectingCopyOnTheAwtMenuRunsTheSelectionsOwnCopyAction() {
        var copied = false
        val handle = assertIs<AwtPopupHandle>(
            backendFor(managerWithCopy(enabled = true) { copied = true }, macOs = true),
        )
        val item = handle.popupMenu.getItem(0)

        item.dispatchEvent(ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.actionCommand))

        assertTrue(copied)
    }

    @Test
    fun selectingCopyOnTheSwingMenuRunsTheSelectionsOwnCopyAction() {
        var copied = false
        val handle = assertIs<SwingPopupHandle>(
            backendFor(managerWithCopy(enabled = true) { copied = true }, macOs = false),
        )

        assertIs<JMenuItem>(handle.popupMenu.getComponent(0)).doClick()

        assertTrue(copied)
    }

    @Test
    fun openingTheMenuBuildsItFromTheSelectionAndShowsItWhereItWasAsked() {
        val built = mutableListOf<FakeHandle>()
        val representation = representationOver(
            entries = { textSelectionMenuEntries(managerWithCopy(enabled = true), copyLabel = "コピー") },
            built = built,
        )

        representation.showAt(12, 34)

        val handle = built.single()
        assertEquals(listOf(LeafSignature("コピー", checked = null, enabled = true)), menuSignature(handle.builtFrom))
        assertEquals(listOf(12 to 34), handle.shownAt)
    }

    @Test
    fun reopeningTheMenuRelabelsItFromTheCurrentSelection() {
        // The selection can gain or lose content between two right-clicks, so the entries have to
        // be read afresh on every open rather than captured when the widgets were built.
        val built = mutableListOf<FakeHandle>()
        var selectionEnabled = false
        val representation = representationOver(
            entries = { textSelectionMenuEntries(managerWithCopy(enabled = selectionEnabled), copyLabel = "コピー") },
            built = built,
        )

        representation.showAt(1, 2)
        selectionEnabled = true
        representation.showAt(3, 4)

        assertEquals(1, built.size, "the widgets are reused rather than rebuilt for the same menu shape")
        val handle = built.single()
        assertEquals(
            listOf(LeafSignature("コピー", checked = null, enabled = true)),
            menuSignature(handle.synced.last()),
        )
    }

    @Test
    fun aSelectionWithNothingToOfferBuildsNoMenuAtAll() {
        // An empty native menu would open as a tiny empty box. Unreachable today — a
        // `SelectionContainer` always offers copy — but it must not build one if it ever is.
        val built = mutableListOf<FakeHandle>()
        val representation = representationOver(
            entries = { textSelectionMenuEntries(FakeTextManager(copy = null), copyLabel = "コピー") },
            built = built,
        )

        representation.showAt(1, 2)

        assertTrue(built.isEmpty())
    }

    @Test
    fun disposingReleasesTheNativeWidgets() {
        val built = mutableListOf<FakeHandle>()
        val representation = representationOver(
            entries = { textSelectionMenuEntries(managerWithCopy(enabled = true), copyLabel = "コピー") },
            built = built,
        )
        representation.showAt(1, 2)

        representation.dispose()

        assertEquals(1, built.single().detachCount)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun withoutANativeWindowItLeavesComposesOwnMenuInPlace() = runDesktopComposeUiTest {
        // LocalNativeWindow is `staticCompositionLocalOf { null }`, so a test host has no window to
        // attach or position a native menu against. The content must still render, with the
        // selection keeping a working menu rather than losing it.
        var menu: TextContextMenu? = null
        setContent {
            NativeTextSelectionContextMenu {
                menu = LocalTextContextMenu.current
                Text("body text")
            }
        }

        onNodeWithText("body text").assertIsDisplayed()
        assertSame(TextContextMenu.Default, menu)
    }
}
