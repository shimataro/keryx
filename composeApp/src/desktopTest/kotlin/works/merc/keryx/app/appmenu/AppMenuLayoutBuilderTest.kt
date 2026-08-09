package works.merc.keryx.app.appmenu

import works.merc.keryx.app.tray.DBusMenuLayoutItem
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.AppMenuShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure [buildAppMenuLayout] / [AppMenuLayout]: recursive layout shape at various
 * recursion depths, property filtering and checkbox mapping, and — load-bearing for correct click
 * dispatch — id stability across rebuilds of an unchanged-shape tree.
 */
class AppMenuLayoutBuilderTest {

    // Pre-order ids: root=0, File=1, Add=2, Separator=3, Quit=4, View=5, Unread=6.
    private fun sampleRoot(
        addLabel: String = "Add",
        unreadChecked: Boolean = true,
    ) = AppMenuRoot(
        listOf(
            AppMenuNode.Menu(
                "File",
                listOf(
                    AppMenuNode.Item(addLabel, enabled = true, onClick = {}),
                    AppMenuNode.Separator,
                    AppMenuNode.Item("Quit", enabled = false, onClick = {}),
                ),
            ),
            AppMenuNode.Menu(
                "View",
                listOf(
                    AppMenuNode.CheckboxItem("Unread", enabled = true, checked = unreadChecked, onCheckedChange = {}),
                ),
            ),
        ),
    )

    private fun DBusMenuLayoutItem.childItems(): List<DBusMenuLayoutItem> =
        children.map { it.value as DBusMenuLayoutItem }

    private fun DBusMenuLayoutItem.prop(name: String): Any? = properties[name]?.value

    @Test
    fun `full-depth layout mirrors the tree shape and ids`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val root = layout.buildItem(APPMENU_ROOT_ID, recursionDepth = -1, propertyNames = emptyList())

        assertEquals(APPMENU_ROOT_ID, root.id)
        assertEquals(listOf(1, 5), root.childItems().map { it.id })

        val file = root.childItems().first { it.id == 1 }
        assertEquals(listOf(2, 3, 4), file.childItems().map { it.id })

        val view = root.childItems().first { it.id == 5 }
        assertEquals(listOf(6), view.childItems().map { it.id })
    }

    @Test
    fun `recursion depth zero returns the parent without children`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val root = layout.buildItem(APPMENU_ROOT_ID, recursionDepth = 0, propertyNames = emptyList())
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun `recursion depth one includes immediate children but not grandchildren`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val root = layout.buildItem(APPMENU_ROOT_ID, recursionDepth = 1, propertyNames = emptyList())
        val file = root.childItems().first { it.id == 1 }
        assertTrue(file.children.isEmpty(), "grandchildren should be excluded at depth 1")
    }

    @Test
    fun `an unknown id yields an empty item instead of throwing`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val item = layout.buildItem(parentId = 999, recursionDepth = -1, propertyNames = emptyList())
        assertEquals(999, item.id)
        assertTrue(item.properties.isEmpty())
        assertTrue(item.children.isEmpty())
    }

    @Test
    fun `property names filter the returned properties`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val item = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = listOf("label"))
        assertEquals(setOf("label"), item.properties.keys)
        assertEquals("Add", item.prop("label"))
    }

    @Test
    fun `an empty property-name list returns all properties`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val item = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = emptyList())
        assertEquals(setOf("type", "label", "enabled", "visible"), item.properties.keys)
    }

    @Test
    fun `a disabled item reports enabled false`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val quit = layout.buildItem(parentId = 4, recursionDepth = 0, propertyNames = emptyList())
        assertEquals(false, quit.prop("enabled"))
    }

    @Test
    fun `a submenu node advertises children-display submenu`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val file = layout.buildItem(parentId = 1, recursionDepth = 0, propertyNames = emptyList())
        assertEquals("submenu", file.prop("children-display"))
    }

    @Test
    fun `a checkbox item maps to toggle-type checkmark and the checked toggle-state`() {
        val checked = buildAppMenuLayout(sampleRoot(unreadChecked = true))
            .buildItem(parentId = 6, recursionDepth = 0, propertyNames = emptyList())
        assertEquals("checkmark", checked.prop("toggle-type"))
        assertEquals(1, checked.prop("toggle-state"))

        val unchecked = buildAppMenuLayout(sampleRoot(unreadChecked = false))
            .buildItem(parentId = 6, recursionDepth = 0, propertyNames = emptyList())
        assertEquals(0, unchecked.prop("toggle-state"))
    }

    @Test
    fun `a separator maps to type separator`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val separator = layout.buildItem(parentId = 3, recursionDepth = 0, propertyNames = emptyList())
        assertEquals("separator", separator.prop("type"))
    }

    @Test
    fun `the dispatch table holds only actionable nodes keyed by their ids`() {
        val layout = buildAppMenuLayout(sampleRoot())
        assertEquals(setOf(2, 4, 6), layout.dispatch.keys)
        assertTrue(layout.dispatch[2] is AppMenuNode.Item)
        assertTrue(layout.dispatch[6] is AppMenuNode.CheckboxItem)
    }

    @Test
    fun `known ids cover every node including the root separators and menus`() {
        val layout = buildAppMenuLayout(sampleRoot())
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), layout.knownIds)
    }

    @Test
    fun `an item with a shortcut reports its dbusmenu shortcut property`() {
        val root = AppMenuRoot(
            listOf(
                AppMenuNode.Menu(
                    "File",
                    listOf(AppMenuNode.Item("Add Feed", enabled = true, shortcut = AppMenuShortcut.AddFeed, onClick = {})),
                ),
            ),
        )
        val layout = buildAppMenuLayout(root)
        val addFeed = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = emptyList())

        assertEquals(listOf(listOf("Control", "N")), addFeed.prop("shortcut"))
        assertEquals("aas", addFeed.properties["shortcut"]?.sig)
    }

    @Test
    fun `an item without a shortcut has no shortcut property`() {
        val layout = buildAppMenuLayout(sampleRoot())
        val add = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = emptyList())
        assertNull(add.prop("shortcut"))
        assertTrue("shortcut" !in add.properties.keys)
    }

    // Ids: root=0, File=1, submenu(2), then one id per item label in order.
    private fun rootWithSubmenu(
        submenuEnabled: Boolean = true,
        itemLabels: List<String> = listOf("A", "B"),
    ) = AppMenuRoot(
        listOf(
            AppMenuNode.Menu(
                "File",
                listOf(
                    AppMenuNode.Menu(
                        "Tags",
                        itemLabels.map { AppMenuNode.Item(it, enabled = true, onClick = {}) },
                        enabled = submenuEnabled,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `a disabled submenu reports enabled false`() {
        val layout = buildAppMenuLayout(rootWithSubmenu(submenuEnabled = false))
        val tags = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = emptyList())
        assertEquals(false, tags.prop("enabled"))
    }

    @Test
    fun `an enabled submenu reports enabled true`() {
        val layout = buildAppMenuLayout(rootWithSubmenu(submenuEnabled = true))
        val tags = layout.buildItem(parentId = 2, recursionDepth = 0, propertyNames = emptyList())
        assertEquals(true, tags.prop("enabled"))
    }

    @Test
    fun `rebuilding with a variable-length submenu still resolves dispatch to the current items`() {
        // Simulates a tag being added between two rebuilds (e.g. Tags/Move-to-folder submenus,
        // whose length follows the live tag/folder lists) — the one case in this codebase where the
        // tree shape genuinely changes at runtime, not just at startup (see AppMenuLayoutBuilder's
        // class doc comment).
        val threeItems = buildAppMenuLayout(rootWithSubmenu(itemLabels = listOf("A", "B", "C")))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), threeItems.knownIds)
        assertEquals("A", (threeItems.dispatch[3] as AppMenuNode.Item).label)
        assertEquals("B", (threeItems.dispatch[4] as AppMenuNode.Item).label)
        assertEquals("C", (threeItems.dispatch[5] as AppMenuNode.Item).label)

        val twoItems = buildAppMenuLayout(rootWithSubmenu(itemLabels = listOf("X", "Y")))
        assertEquals(setOf(0, 1, 2, 3, 4), twoItems.knownIds)
        assertEquals("X", (twoItems.dispatch[3] as AppMenuNode.Item).label)
        assertEquals("Y", (twoItems.dispatch[4] as AppMenuNode.Item).label)
        // Id 5 no longer exists in this rebuild's layout — a click against the previous rebuild's
        // stale id 5 would be resolved by the host re-fetching first (AboutToShow), not by this
        // layout still knowing about it.
        assertTrue(5 !in twoItems.knownIds)
    }

    @Test
    fun `ids are stable across rebuilds of an unchanged-shape tree`() {
        val first = buildAppMenuLayout(sampleRoot(addLabel = "Add", unreadChecked = true))
        val second = buildAppMenuLayout(sampleRoot(addLabel = "追加", unreadChecked = false))

        // Same shape, different labels/checked/lambdas -> identical id assignment.
        assertEquals(first.knownIds, second.knownIds)
        assertEquals(first.dispatch.keys, second.dispatch.keys)

        // The "Add" item stays at id 2 even though its label changed.
        val firstAdd = first.buildItem(2, 0, listOf("label")).properties["label"]?.value
        val secondAdd = second.buildItem(2, 0, listOf("label")).properties["label"]?.value
        assertEquals("Add", firstAdd)
        assertEquals("追加", secondAdd)
    }
}
