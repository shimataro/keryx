package works.merc.keryx.app.ui.menu

import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers [buildAppMenuTree]: the fixed menu shape, the `isMacOs` omissions, the enabled/checked
 * flags mirroring [MenuUiState] 1:1, and the optional "Show Menu Bar" toggle.
 *
 * `isMacOs` is a process constant, so assertions that depend on it branch on the actual value
 * (this suite runs on macOS locally and Linux in CI).
 */
class AppMenuTreeTest {

    private fun labels() = AppMenuLabels(
        fileMenu = "File", addFeed = "AddFeed", addFolder = "AddFolder", addTag = "AddTag",
        importOpml = "Import", exportOpml = "Export", closeWindow = "Close", settings = "Settings", quit = "Quit",
        viewMenu = "View", search = "Search", unreadOnly = "UnreadOnly", toggleSort = "ToggleSort",
        markAllRead = "MarkAllRead", showMenuBar = "ShowMenuBar",
        articleMenu = "Article", toggleRead = "ToggleRead", toggleStar = "ToggleStar",
        openInBrowser = "OpenInBrowser", copyUrl = "CopyUrl",
        feedMenu = "Feed", refreshAll = "RefreshAll", syncNow = "SyncNow",
        helpMenu = "Help", website = "Website", projectPage = "ProjectPage", about = "About",
    )

    private var toggledTo: Boolean? = null

    private fun actions() = AppMenuActions(
        addFeed = {}, addFolder = {}, addTag = {}, importOpml = {}, exportOpml = {},
        closeWindow = {}, openSettings = {}, quit = {}, focusSearch = {}, setUnreadOnly = {},
        toggleSort = {}, markAllRead = {}, toggleRead = {}, toggleStar = {}, openInBrowser = {},
        copyUrl = {}, refreshAll = {}, sync = {}, openWebsite = {}, openProjectPage = {}, about = {},
    )

    private fun enabledUi() = computeMenuUiState(
        screen = Screen.Home, hasSelectedArticle = true, selectedArticleHasUrl = true,
        feedRefreshing = false, syncing = false, cloudConnected = true,
        filterIsSearch = false, unreadOnly = true,
    )

    private fun disabledUi() = computeMenuUiState(
        screen = Screen.Setup, hasSelectedArticle = false, selectedArticleHasUrl = false,
        feedRefreshing = true, syncing = true, cloudConnected = false,
        filterIsSearch = true, unreadOnly = false,
    )

    private fun tree(ui: MenuUiState, toggle: MenuBarToggle? = null): AppMenuRoot =
        buildAppMenuTree(ui, labels(), actions(), toggle)

    private fun AppMenuRoot.menu(label: String): AppMenuNode.Menu = menus.first { it.label == label }

    private fun AppMenuNode.Menu.item(label: String): AppMenuNode.Item =
        items.filterIsInstance<AppMenuNode.Item>().first { it.label == label }

    private fun AppMenuNode.Menu.checkbox(label: String): AppMenuNode.CheckboxItem? =
        items.filterIsInstance<AppMenuNode.CheckboxItem>().firstOrNull { it.label == label }

    @Test
    fun `the tree has the five top-level menus in order`() {
        val root = tree(enabledUi())
        assertEquals(listOf("File", "View", "Article", "Feed", "Help"), root.menus.map { it.label })
    }

    @Test
    fun `enabled flags mirror MenuUiState one to one`() {
        val ui = enabledUi()
        val root = tree(ui)

        assertEquals(ui.addItemsEnabled, root.menu("File").item("AddFeed").enabled)
        assertEquals(ui.opmlEnabled, root.menu("File").item("Import").enabled)
        assertEquals(ui.searchEnabled, root.menu("View").item("Search").enabled)
        assertEquals(ui.toggleSortEnabled, root.menu("View").item("ToggleSort").enabled)
        assertEquals(ui.markAllReadEnabled, root.menu("View").item("MarkAllRead").enabled)
        assertEquals(ui.articleActionsEnabled, root.menu("Article").item("ToggleRead").enabled)
        assertEquals(ui.urlActionsEnabled, root.menu("Article").item("OpenInBrowser").enabled)
        assertEquals(ui.refreshAllEnabled, root.menu("Feed").item("RefreshAll").enabled)
        assertEquals(ui.syncEnabled, root.menu("Feed").item("SyncNow").enabled)
    }

    @Test
    fun `disabled state propagates to every gated item`() {
        val ui = disabledUi()
        val root = tree(ui)

        assertEquals(false, root.menu("File").item("AddFeed").enabled)
        assertEquals(false, root.menu("View").item("Search").enabled)
        assertEquals(false, root.menu("Feed").item("RefreshAll").enabled)
        assertEquals(false, root.menu("Feed").item("SyncNow").enabled)
        assertEquals(false, root.menu("Article").item("ToggleRead").enabled)
    }

    @Test
    fun `the unread-only checkbox reflects the ui checked flag`() {
        assertEquals(true, tree(enabledUi()).menu("View").checkbox("UnreadOnly")!!.checked)
        assertEquals(false, tree(disabledUi()).menu("View").checkbox("UnreadOnly")!!.checked)
    }

    @Test
    fun `website and project-page items are always present`() {
        val help = tree(enabledUi()).menu("Help")
        assertNotNull(help.items.filterIsInstance<AppMenuNode.Item>().firstOrNull { it.label == "Website" })
        assertNotNull(help.items.filterIsInstance<AppMenuNode.Item>().firstOrNull { it.label == "ProjectPage" })
    }

    @Test
    fun `settings quit and about follow the platform`() {
        val root = tree(enabledUi())
        val file = root.menu("File")
        val help = root.menu("Help")
        val hasSettings = file.items.filterIsInstance<AppMenuNode.Item>().any { it.label == "Settings" }
        val hasQuit = file.items.filterIsInstance<AppMenuNode.Item>().any { it.label == "Quit" }
        val hasAbout = help.items.filterIsInstance<AppMenuNode.Item>().any { it.label == "About" }
        // On macOS these live in the native app menu and are omitted here; elsewhere they are present.
        assertEquals(!isMacOs, hasSettings)
        assertEquals(!isMacOs, hasQuit)
        assertEquals(!isMacOs, hasAbout)
    }

    @Test
    fun `the show-menu-bar checkbox appears only when a toggle is supplied`() {
        assertNull(tree(enabledUi(), toggle = null).menu("View").checkbox("ShowMenuBar"))

        val checkbox = tree(enabledUi(), toggle = MenuBarToggle(visible = false, onToggle = { toggledTo = it }))
            .menu("View").checkbox("ShowMenuBar")
        assertNotNull(checkbox)
        assertEquals(false, checkbox.checked)
        assertEquals(AppMenuShortcut.ShowMenuBar, checkbox.shortcut)
    }

    @Test
    fun `the show-menu-bar checkbox reflects the current visibility and forwards its toggle`() {
        val checkbox = tree(enabledUi(), toggle = MenuBarToggle(visible = true, onToggle = { toggledTo = it }))
            .menu("View").checkbox("ShowMenuBar")!!
        assertTrue(checkbox.checked)

        checkbox.onCheckedChange(false)
        assertEquals(false, toggledTo)
    }

    @Test
    fun `shipped accelerators are attached to their items`() {
        val root = tree(enabledUi())
        assertEquals(AppMenuShortcut.AddFeed, root.menu("File").item("AddFeed").shortcut)
        assertEquals(AppMenuShortcut.CloseWindow, root.menu("File").item("Close").shortcut)
        assertEquals(AppMenuShortcut.RefreshAll, root.menu("Feed").item("RefreshAll").shortcut)
    }
}
