package works.merc.keryx.app.ui.menu

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
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

    private fun tag(id: String, name: String) =
        Tags(id, name, color = null, sort_order = 0L, deleted_at = null, updated_at = 0L, created_at = 0L)

    private fun folder(id: String, name: String) =
        Folders(id, name, sort_order = 0L, deleted_at = null, updated_at = 0L, created_at = 0L)

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
        feedCopyUrl = "FeedCopyUrl", feedCopySiteUrl = "FeedCopySiteUrl", feedOpenSite = "FeedOpenSite",
        helpMenu = "Help", website = "Website", projectPage = "ProjectPage", about = "About",
    )

    private var toggledTo: Boolean? = null
    private var toggledTagId: String? = null
    private var toggledTagAttached: Boolean? = null
    private var movedToFolderId: String? = null

    private fun actions() = AppMenuActions(
        addFeed = {}, addFolder = {}, addTag = {}, importOpml = {}, exportOpml = {},
        closeWindow = {}, openSettings = {}, quit = {}, focusSearch = {}, setUnreadOnly = {},
        toggleSort = {}, markAllRead = {}, toggleRead = {}, toggleStar = {}, openInBrowser = {},
        copyUrl = {}, refreshAll = {}, sync = {},
        refreshSelectedFeed = {},
        toggleFeedTag = { tagId, attached -> toggledTagId = tagId; toggledTagAttached = attached },
        moveFeedToFolder = { movedToFolderId = it },
        renameSelectedFeed = {}, unsubscribeSelectedFeed = {},
        copyFeedUrl = {}, copyFeedSiteUrl = {}, openFeedSite = {},
        openWebsite = {}, openProjectPage = {}, about = {},
    )

    private fun enabledUi() = computeMenuUiState(
        screen = Screen.Home, hasSelectedArticle = true, selectedArticleHasUrl = true,
        feedRefreshing = false, syncing = false, cloudConnected = true,
        filter = ArticleFilter.All, unreadOnly = true,
        hasSelectedFeed = true, hasRenamableSelection = true, selectedFeedHasSiteUrl = true,
    )

    private fun disabledUi() = computeMenuUiState(
        screen = Screen.Setup, hasSelectedArticle = false, selectedArticleHasUrl = false,
        feedRefreshing = true, syncing = true, cloudConnected = false,
        filter = ArticleFilter.Search, unreadOnly = false,
        hasSelectedFeed = false, hasRenamableSelection = false,
    )

    /** A folder (or tag) selected: a rename/delete target, but no feed-specific selection. */
    private fun folderSelectedUi() = computeMenuUiState(
        screen = Screen.Home, hasSelectedArticle = false, selectedArticleHasUrl = false,
        feedRefreshing = false, syncing = false, cloudConnected = true,
        filter = ArticleFilter.Folder("fo1"), unreadOnly = false,
        hasSelectedFeed = false, hasRenamableSelection = true,
    )

    private fun starredFilterUi() = computeMenuUiState(
        screen = Screen.Home, hasSelectedArticle = true, selectedArticleHasUrl = true,
        feedRefreshing = false, syncing = false, cloudConnected = true,
        filter = ArticleFilter.Starred, unreadOnly = true,
        hasSelectedFeed = true, hasRenamableSelection = true,
    )

    private fun selectedFeedMenu(
        tags: List<Tags> = emptyList(),
        attachedTagIds: Set<String> = emptySet(),
        folders: List<Folders> = emptyList(),
        currentFolderId: String? = null,
    ) = SelectedFeedMenuData(tags, attachedTagIds, folders, currentFolderId)

    private fun tree(
        ui: MenuUiState,
        toggle: MenuBarToggle? = null,
        feedMenu: SelectedFeedMenuData = selectedFeedMenu(),
    ): AppMenuRoot = buildAppMenuTree(ui, labels(), actions(), toggle, feedMenu)

    private fun AppMenuRoot.menu(label: String): AppMenuNode.Menu = menus.first { it.label == label }

    private fun AppMenuNode.Menu.item(label: String): AppMenuNode.Item =
        items.filterIsInstance<AppMenuNode.Item>().first { it.label == label }

    private fun AppMenuNode.Menu.checkbox(label: String): AppMenuNode.CheckboxItem? =
        items.filterIsInstance<AppMenuNode.CheckboxItem>().firstOrNull { it.label == label }

    private fun AppMenuNode.Menu.submenu(label: String): AppMenuNode.Menu =
        items.filterIsInstance<AppMenuNode.Menu>().first { it.label == label }

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
        assertEquals(ui.unreadOnlyEnabled, root.menu("View").checkbox("UnreadOnly")!!.enabled)
        assertEquals(ui.toggleSortEnabled, root.menu("View").item("ToggleSort").enabled)
        assertEquals(ui.markAllReadEnabled, root.menu("View").item("MarkAllRead").enabled)
        assertEquals(ui.articleActionsEnabled, root.menu("Article").item("ToggleRead").enabled)
        assertEquals(ui.urlActionsEnabled, root.menu("Article").item("OpenInBrowser").enabled)
        assertEquals(ui.refreshAllEnabled, root.menu("Feed").item("RefreshAll").enabled)
        assertEquals(ui.syncEnabled, root.menu("Feed").item("SyncNow").enabled)
        assertEquals(ui.feedActionsEnabled, root.menu("Feed").item("FeedRefresh").enabled)
        assertEquals(ui.renameOrDeleteEnabled, root.menu("Feed").item("FeedRename").enabled)
        assertEquals(ui.renameOrDeleteEnabled, root.menu("Feed").item("FeedUnsubscribe").enabled)
        assertEquals(ui.feedActionsEnabled, root.menu("Feed").submenu("AssignTags").enabled)
        assertEquals(ui.feedActionsEnabled, root.menu("Feed").submenu("MoveToFolder").enabled)
        assertEquals(ui.feedActionsEnabled, root.menu("Feed").item("FeedCopyUrl").enabled)
        assertEquals(ui.feedSiteUrlActionsEnabled, root.menu("Feed").item("FeedCopySiteUrl").enabled)
        assertEquals(ui.feedSiteUrlActionsEnabled, root.menu("Feed").item("FeedOpenSite").enabled)
    }

    @Test
    fun `rename and unsubscribe stay enabled for a folder or tag selection with no feed selected`() {
        // They act on whatever feed list item is selected, unlike the feed-specific items above.
        val ui = folderSelectedUi()
        val root = tree(ui)

        assertEquals(true, root.menu("Feed").item("FeedRename").enabled)
        assertEquals(true, root.menu("Feed").item("FeedUnsubscribe").enabled)
        assertEquals(false, root.menu("Feed").item("FeedRefresh").enabled)
        assertEquals(false, root.menu("Feed").submenu("AssignTags").enabled)
        assertEquals(false, root.menu("Feed").submenu("MoveToFolder").enabled)
        assertEquals(false, root.menu("Feed").item("FeedCopyUrl").enabled)
        assertEquals(false, root.menu("Feed").item("FeedCopySiteUrl").enabled)
        assertEquals(false, root.menu("Feed").item("FeedOpenSite").enabled)
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
        assertEquals(false, root.menu("Feed").item("FeedRefresh").enabled)
        assertEquals(false, root.menu("Feed").item("FeedRename").enabled)
        assertEquals(false, root.menu("Feed").item("FeedUnsubscribe").enabled)
        assertEquals(false, root.menu("Feed").submenu("AssignTags").enabled)
        assertEquals(false, root.menu("Feed").submenu("MoveToFolder").enabled)
        assertEquals(false, root.menu("Feed").item("FeedCopyUrl").enabled)
        assertEquals(false, root.menu("Feed").item("FeedCopySiteUrl").enabled)
        assertEquals(false, root.menu("Feed").item("FeedOpenSite").enabled)
    }

    @Test
    fun `the tags submenu has one checkbox per tag reflecting attachment`() {
        val root = tree(
            enabledUi(),
            feedMenu = selectedFeedMenu(
                tags = listOf(tag("t1", "Kotlin"), tag("t2", "News")),
                attachedTagIds = setOf("t1"),
            ),
        )
        val assignTags = root.menu("Feed").submenu("AssignTags")
        assertEquals(listOf("Kotlin", "News"), assignTags.items.filterIsInstance<AppMenuNode.CheckboxItem>().map { it.label })
        assertTrue(assignTags.items.filterIsInstance<AppMenuNode.CheckboxItem>().first { it.label == "Kotlin" }.checked)
        assertEquals(false, assignTags.items.filterIsInstance<AppMenuNode.CheckboxItem>().first { it.label == "News" }.checked)
    }

    @Test
    fun `clicking a tag checkbox invokes toggleFeedTag with its id and the new state`() {
        val root = tree(enabledUi(), feedMenu = selectedFeedMenu(tags = listOf(tag("t1", "Kotlin"))))
        root.menu("Feed").submenu("AssignTags").items.filterIsInstance<AppMenuNode.CheckboxItem>().first().onCheckedChange(true)
        assertEquals("t1", toggledTagId)
        assertEquals(true, toggledTagAttached)
    }

    @Test
    fun `the move-to-folder submenu has a no-folder entry plus one per folder reflecting the current folder`() {
        val root = tree(
            enabledUi(),
            feedMenu = selectedFeedMenu(folders = listOf(folder("d1", "Tech"), folder("d2", "News")), currentFolderId = "d2"),
        )
        val moveToFolder = root.menu("Feed").submenu("MoveToFolder")
        val boxes = moveToFolder.items.filterIsInstance<AppMenuNode.CheckboxItem>()
        assertEquals(listOf("NoFolder", "Tech", "News"), boxes.map { it.label })
        assertTrue(boxes.first { it.label == "News" }.checked)
        assertEquals(false, boxes.first { it.label == "NoFolder" }.checked)
        assertEquals(false, boxes.first { it.label == "Tech" }.checked)
    }

    @Test
    fun `clicking a folder entry invokes moveFeedToFolder with its id, and no-folder with null`() {
        val root = tree(enabledUi(), feedMenu = selectedFeedMenu(folders = listOf(folder("d1", "Tech"))))
        val moveToFolder = root.menu("Feed").submenu("MoveToFolder")
        moveToFolder.items.filterIsInstance<AppMenuNode.CheckboxItem>().first { it.label == "Tech" }.onCheckedChange(true)
        assertEquals("d1", movedToFolderId)

        moveToFolder.items.filterIsInstance<AppMenuNode.CheckboxItem>().first { it.label == "NoFolder" }.onCheckedChange(true)
        assertNull(movedToFolderId)
    }

    @Test
    fun `the unread-only checkbox reflects the ui checked flag`() {
        assertEquals(true, tree(enabledUi()).menu("View").checkbox("UnreadOnly")!!.checked)
        assertEquals(false, tree(disabledUi()).menu("View").checkbox("UnreadOnly")!!.checked)
    }

    @Test
    fun `the unread-only checkbox stays enabled in the starred filter like other View items`() {
        val ui = starredFilterUi()
        val root = tree(ui)
        assertEquals(true, root.menu("View").checkbox("UnreadOnly")!!.enabled)
        assertEquals(true, root.menu("View").item("Search").enabled)
        assertEquals(true, root.menu("View").item("MarkAllRead").enabled)
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
        assertEquals(AppMenuShortcut.Search, root.menu("View").item("Search").shortcut)
        assertEquals(AppMenuShortcut.ImportOpml, root.menu("File").item("Import").shortcut)
        assertEquals(AppMenuShortcut.ExportOpml, root.menu("File").item("Export").shortcut)
        assertEquals(AppMenuShortcut.UnreadOnly, root.menu("View").checkbox("UnreadOnly")!!.shortcut)
    }

    @Test
    fun `the selected-item accelerators are attached to their items`() {
        val root = tree(enabledUi())
        assertEquals(AppMenuShortcut.ToggleRead, root.menu("Article").item("ToggleRead").shortcut)
        assertEquals(AppMenuShortcut.ToggleStar, root.menu("Article").item("ToggleStar").shortcut)
        assertEquals(AppMenuShortcut.OpenInBrowser, root.menu("Article").item("OpenInBrowser").shortcut)
        assertEquals(AppMenuShortcut.CopyUrl, root.menu("Article").item("CopyUrl").shortcut)
        assertEquals(AppMenuShortcut.FeedRefresh, root.menu("Feed").item("FeedRefresh").shortcut)
        assertEquals(AppMenuShortcut.FeedRename, root.menu("Feed").item("FeedRename").shortcut)
        assertEquals(AppMenuShortcut.FeedUnsubscribe, root.menu("Feed").item("FeedUnsubscribe").shortcut)
    }
}
