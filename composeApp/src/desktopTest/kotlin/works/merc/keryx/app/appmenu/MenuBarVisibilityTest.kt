package works.merc.keryx.app.appmenu

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.ui.menu.AppMenuActions
import works.merc.keryx.app.ui.menu.AppMenuLabels
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuShortcut
import works.merc.keryx.app.ui.menu.MenuBarToggle
import works.merc.keryx.app.ui.menu.SelectedFeedMenuData
import works.merc.keryx.app.ui.menu.buildAppMenuTree
import works.merc.keryx.app.ui.menu.computeMenuUiState
import works.merc.keryx.app.ui.navigation.Screen
import java.awt.Frame
import java.awt.Panel
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure parts of [MenuBarVisibility]: the AWT key-code mapping, the shortcut → node
 * matcher the [MenuShortcutDispatcher] delegates to, action invocation, [MenuShortcutDispatcher]'s
 * window-scoping via `dispatchKeyEvent` called directly, and the `local_settings.json` visibility
 * persistence round-trip. The actual `KeyboardFocusManager` registration is not testable.
 */
class MenuBarVisibilityTest {

    private var addFeedCalled = false
    private var refreshCalled = false
    private var toggledTo: Boolean? = null

    private fun labels() = AppMenuLabels(
        fileMenu = "File", addFeed = "AddFeed", addFolder = "AddFolder", addTag = "AddTag",
        importOpml = "Import", exportOpml = "Export", closeWindow = "Close", settings = "Settings", quit = "Quit",
        viewMenu = "View", search = "Search", unreadOnly = "UnreadOnly", toggleSort = "ToggleSort",
        markAllRead = "MarkAllRead", showMenuBar = "ShowMenuBar",
        articleMenu = "Article", toggleRead = "ToggleRead", toggleStar = "ToggleStar",
        openInBrowser = "OpenInBrowser", copyUrl = "CopyUrl",
        feedMenu = "Feed", refreshAll = "RefreshAll", syncNow = "SyncNow",
        feedRefresh = "FeedRefresh", feedAssignTags = "AssignTags", feedMoveToFolder = "MoveToFolder",
        feedNoFolder = "NoFolder", feedRename = "FeedRename", feedUnsubscribe = "FeedUnsubscribe",
        helpMenu = "Help", website = "Website", projectPage = "ProjectPage", about = "About",
    )

    private fun actions() = AppMenuActions(
        addFeed = { addFeedCalled = true }, addFolder = {}, addTag = {}, importOpml = {}, exportOpml = {},
        closeWindow = {}, openSettings = {}, quit = {}, focusSearch = {}, setUnreadOnly = {},
        toggleSort = {}, markAllRead = {}, toggleRead = {}, toggleStar = {}, openInBrowser = {},
        copyUrl = {}, refreshAll = { refreshCalled = true }, sync = {},
        refreshSelectedFeed = {}, toggleFeedTag = { _, _ -> }, moveFeedToFolder = {},
        renameSelectedFeed = {}, unsubscribeSelectedFeed = {},
        openWebsite = {}, openProjectPage = {}, about = {},
    )

    private fun tree(menuBarVisible: Boolean = false) = buildAppMenuTree(
        ui = computeMenuUiState(
            screen = Screen.Home, hasSelectedArticle = true, selectedArticleHasUrl = true,
            feedRefreshing = false, syncing = false, cloudConnected = true,
            filter = ArticleFilter.All, unreadOnly = false,
        ),
        labels = labels(),
        actions = actions(),
        menuBarToggle = MenuBarToggle(visible = menuBarVisible, onToggle = { toggledTo = it }),
        selectedFeedMenu = SelectedFeedMenuData(emptyList(), emptySet(), emptyList(), null),
    )

    // --- AWT key-code mapping ---

    @Test
    fun `shortcuts map to their awt virtual key codes`() {
        assertEquals(KeyEvent.VK_N, AppMenuShortcut.AddFeed.awtKeyCode())
        assertEquals(KeyEvent.VK_W, AppMenuShortcut.CloseWindow.awtKeyCode())
        assertEquals(KeyEvent.VK_COMMA, AppMenuShortcut.Settings.awtKeyCode())
        assertEquals(KeyEvent.VK_Q, AppMenuShortcut.Quit.awtKeyCode())
        assertEquals(KeyEvent.VK_R, AppMenuShortcut.RefreshAll.awtKeyCode())
        assertEquals(KeyEvent.VK_M, AppMenuShortcut.ShowMenuBar.awtKeyCode())
    }

    // --- shortcut matching ---

    @Test
    fun `ctrl plus the accelerator key resolves the right item`() {
        val addFeed = matchMenuShortcut(tree(), KeyEvent.VK_N, ctrl = true, meta = false)
        assertTrue(addFeed is AppMenuNode.Item && addFeed.label == "AddFeed")

        val refresh = matchMenuShortcut(tree(), KeyEvent.VK_R, ctrl = true, meta = false)
        assertTrue(refresh is AppMenuNode.Item && refresh.label == "RefreshAll")

        val showMenuBar = matchMenuShortcut(tree(), KeyEvent.VK_M, ctrl = true, meta = false)
        assertTrue(showMenuBar is AppMenuNode.CheckboxItem && showMenuBar.label == "ShowMenuBar")
    }

    @Test
    fun `a wrong modifier combination does not match`() {
        // AddFeed requires Ctrl (not Meta) and no Shift.
        assertNull(matchMenuShortcut(tree(), KeyEvent.VK_N, ctrl = false, meta = true))
        assertNull(matchMenuShortcut(tree(), KeyEvent.VK_N, ctrl = false, meta = false))
        assertNull(matchMenuShortcut(tree(), KeyEvent.VK_N, ctrl = true, meta = false, shift = true))
    }

    @Test
    fun `ctrl shift plus the accelerator key resolves the selected-item entries, distinct from their plain-ctrl counterparts`() {
        val toggleRead = matchMenuShortcut(tree(), KeyEvent.VK_U, ctrl = true, meta = false, shift = true)
        assertTrue(toggleRead is AppMenuNode.Item && toggleRead.label == "ToggleRead")

        val feedRefresh = matchMenuShortcut(tree(), KeyEvent.VK_R, ctrl = true, meta = false, shift = true)
        assertTrue(feedRefresh is AppMenuNode.Item && feedRefresh.label == "FeedRefresh")

        // Ctrl+R (no Shift) still means RefreshAll, not FeedRefresh, even though both use VK_R.
        val refreshAll = matchMenuShortcut(tree(), KeyEvent.VK_R, ctrl = true, meta = false, shift = false)
        assertTrue(refreshAll is AppMenuNode.Item && refreshAll.label == "RefreshAll")
    }

    @Test
    fun `rename and unsubscribe resolve on their original bare key, no modifier`() {
        val renameKey = if (isMacOs) KeyEvent.VK_ENTER else KeyEvent.VK_F2
        val rename = matchMenuShortcut(tree(), renameKey, ctrl = false, meta = false)
        assertTrue(rename is AppMenuNode.Item && rename.label == "FeedRename")

        val unsubscribe = matchMenuShortcut(tree(), KeyEvent.VK_DELETE, ctrl = false, meta = false)
        assertTrue(unsubscribe is AppMenuNode.Item && unsubscribe.label == "FeedUnsubscribe")
    }

    @Test
    fun `a key with no accelerator does not match`() {
        assertNull(matchMenuShortcut(tree(), KeyEvent.VK_Z, ctrl = true, meta = false))
    }

    // --- action invocation ---

    @Test
    fun `invoking a matched item runs its click action`() {
        matchMenuShortcut(tree(), KeyEvent.VK_N, ctrl = true, meta = false)!!.invokeAction()
        assertTrue(addFeedCalled)

        matchMenuShortcut(tree(), KeyEvent.VK_R, ctrl = true, meta = false)!!.invokeAction()
        assertTrue(refreshCalled)
    }

    @Test
    fun `invoking the show-menu-bar checkbox toggles the current visibility`() {
        // Bar currently hidden -> Ctrl+M requests showing it.
        matchMenuShortcut(tree(menuBarVisible = false), KeyEvent.VK_M, ctrl = true, meta = false)!!.invokeAction()
        assertEquals(true, toggledTo)

        // Bar currently shown -> Ctrl+M requests hiding it.
        matchMenuShortcut(tree(menuBarVisible = true), KeyEvent.VK_M, ctrl = true, meta = false)!!.invokeAction()
        assertEquals(false, toggledTo)
    }

    @Test
    fun `isEnabled reflects an item's enabled flag`() {
        val enabled = AppMenuNode.Item("x", enabled = true, onClick = {})
        val disabled = AppMenuNode.Item("y", enabled = false, onClick = {})
        assertTrue(enabled.isEnabled())
        assertTrue(!disabled.isEnabled())
        assertTrue(AppMenuNode.Separator.isEnabled())
    }

    // --- dispatcher window scoping ---

    private fun ctrlKeyEvent(source: java.awt.Component, keyCode: Int) = KeyEvent(
        source,
        KeyEvent.KEY_PRESSED,
        System.currentTimeMillis(),
        InputEvent.CTRL_DOWN_MASK,
        keyCode,
        KeyEvent.CHAR_UNDEFINED,
    )

    @Test
    fun `dispatchKeyEvent rejects an event whose window is not the accepted one`() {
        val mainFrame = Frame()
        val otherDialog = java.awt.Dialog(mainFrame)
        val otherComponent = Panel().also { otherDialog.add(it) }
        val dispatcher = MenuShortcutDispatcher(currentTree = { tree() }, acceptsWindow = { it === mainFrame })

        val handled = dispatcher.dispatchKeyEvent(ctrlKeyEvent(otherComponent, KeyEvent.VK_N))

        assertFalse(handled)
        assertFalse(addFeedCalled)
    }

    @Test
    fun `dispatchKeyEvent still matches an event from the accepted window`() {
        val mainFrame = Frame()
        val mainComponent = Panel().also { mainFrame.add(it) }
        val dispatcher = MenuShortcutDispatcher(currentTree = { tree() }, acceptsWindow = { it === mainFrame })

        val handled = dispatcher.dispatchKeyEvent(ctrlKeyEvent(mainComponent, KeyEvent.VK_N))

        assertTrue(handled)
        assertTrue(addFeedCalled)
    }

    @Test
    fun `dispatchKeyEvent accepts any window by default`() {
        val someFrame = Frame()
        val component = Panel().also { someFrame.add(it) }
        val dispatcher = MenuShortcutDispatcher(currentTree = { tree() })

        val handled = dispatcher.dispatchKeyEvent(ctrlKeyEvent(component, KeyEvent.VK_N))

        assertTrue(handled)
        assertTrue(addFeedCalled)
    }

    // --- persistence round-trip ---

    @Test
    fun `menu bar visibility round-trips through local settings`() {
        val dir = Files.createTempDirectory("keryx-menubar").toString()
        val store = LocalSettingsStore(dirOverride = dir)

        store.save(LocalSettings().withMenuBarVisible(false))
        assertEquals(false, store.load().appMenuBarVisible)

        store.save(LocalSettings().withMenuBarVisible(true))
        assertEquals(true, store.load().appMenuBarVisible)
    }

    @Test
    fun `the default menu bar visibility preference is null (auto)`() {
        assertNull(LocalSettings().appMenuBarVisible)
    }
}
