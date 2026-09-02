package works.merc.keryx.app.tray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayMenuModelTest {
    private val hidden = TrayMenuState(toggleLabel = "表示", quitLabel = "終了")
    private val shown = TrayMenuState(toggleLabel = "非表示", quitLabel = "終了")

    private fun DBusMenuLayoutItem.childItems(): List<DBusMenuLayoutItem> =
        children.map { it.value as DBusMenuLayoutItem }

    private fun DBusMenuLayoutItem.label(): String? = properties["label"]?.value as String?

    @Test
    fun `root layout exposes the toggle and quit items in order`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, recursionDepth = -1, propertyNames = emptyList(), state = hidden)

        assertEquals(MENU_ROOT_ID, layout.id)
        assertEquals("submenu", layout.properties["children-display"]?.value)
        assertEquals(listOf(MENU_TOGGLE_ID, MENU_QUIT_ID), layout.childItems().map { it.id })
    }

    @Test
    fun `toggle label is the show label while the window is hidden`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), hidden)
        assertEquals("表示", layout.childItems().first().label())
    }

    @Test
    fun `toggle label is the hide label while the window is visible`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), shown)
        assertEquals("非表示", layout.childItems().first().label())
    }

    @Test
    fun `recursionDepth zero returns the root without children`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, recursionDepth = 0, propertyNames = emptyList(), state = hidden)
        assertEquals(MENU_ROOT_ID, layout.id)
        assertTrue(layout.children.isEmpty())
    }

    @Test
    fun `requesting a leaf returns just that leaf`() {
        val layout = buildMenuLayout(MENU_TOGGLE_ID, -1, emptyList(), hidden)
        assertEquals(MENU_TOGGLE_ID, layout.id)
        assertEquals("表示", layout.label())
        assertTrue(layout.children.isEmpty())
    }

    @Test
    fun `an unknown parent id yields an empty item instead of throwing`() {
        val layout = buildMenuLayout(parentId = 99, recursionDepth = -1, propertyNames = emptyList(), state = hidden)
        assertEquals(99, layout.id)
        assertTrue(layout.properties.isEmpty())
        assertTrue(layout.children.isEmpty())
    }

    @Test
    fun `propertyNames filters the returned properties`() {
        val properties = menuItemProperties(MENU_TOGGLE_ID, hidden, propertyNames = listOf("label"))
        assertEquals(setOf("label"), properties.keys)
    }

    @Test
    fun `an empty propertyNames returns every property`() {
        val properties = menuItemProperties(MENU_TOGGLE_ID, hidden)
        assertEquals(setOf("type", "label", "enabled", "visible"), properties.keys)
    }

    @Test
    fun `leaf properties use the D-Bus types dbusmenu expects`() {
        val properties = menuItemProperties(MENU_QUIT_ID, hidden)
        assertEquals("s", properties.getValue("label").sig)
        assertEquals("s", properties.getValue("type").sig)
        assertEquals("b", properties.getValue("enabled").sig)
        assertEquals("b", properties.getValue("visible").sig)
    }

    @Test
    fun `underscores in labels are escaped so dbusmenu does not read them as mnemonics`() {
        assertEquals("a__b", escapeMenuLabel("a_b"))
        assertEquals("____", escapeMenuLabel("__"))
        assertEquals("plain", escapeMenuLabel("plain"))

        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), TrayMenuState("a_b", "c_d"))
        assertEquals(listOf("a__b", "c__d"), layout.childItems().map { it.label() })
    }

    @Test
    fun `children are wrapped in variants carrying the dbusmenu node signature`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), hidden)
        layout.children.forEach { assertEquals(MENU_LAYOUT_SIGNATURE, it.sig) }
    }

    // --- update entry ---

    @Test
    fun `no update entry keeps the original two-item menu shape`() {
        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), hidden)
        assertEquals(listOf(MENU_TOGGLE_ID, MENU_QUIT_ID), layout.childItems().map { it.id })
    }

    @Test
    fun `an update entry is inserted ahead of a separator then toggle and quit`() {
        val withUpdate = hidden.copy(update = TrayUpdateEntry("Download update 2.0.0", enabled = true))
        val layout = buildMenuLayout(MENU_ROOT_ID, -1, emptyList(), withUpdate)
        assertEquals(
            listOf(MENU_UPDATE_ID, MENU_SEPARATOR_ID, MENU_TOGGLE_ID, MENU_QUIT_ID),
            layout.childItems().map { it.id },
        )
    }

    @Test
    fun `the update entry carries its own label and enabled state`() {
        val withUpdate = hidden.copy(update = TrayUpdateEntry("Download update 2.0.0", enabled = false))
        val properties = menuItemProperties(MENU_UPDATE_ID, withUpdate)
        assertEquals("Download update 2.0.0", properties.getValue("label").value)
        assertEquals(false, properties.getValue("enabled").value)
    }

    @Test
    fun `the separator has no label and is typed as a separator`() {
        val withUpdate = hidden.copy(update = TrayUpdateEntry("Download update 2.0.0", enabled = true))
        val properties = menuItemProperties(MENU_SEPARATOR_ID, withUpdate)
        assertEquals("separator", properties.getValue("type").value)
    }

    @Test
    fun `the update entry properties are empty when no update is offered`() {
        assertTrue(menuItemProperties(MENU_UPDATE_ID, hidden).isEmpty())
    }

    // --- roundedTrayProgressPercent ---

    @Test
    fun `progress rounds down to the nearest 5 percent`() {
        assertEquals(60, roundedTrayProgressPercent(bytesDone = 62, bytesTotal = 100))
        assertEquals(0, roundedTrayProgressPercent(bytesDone = 4, bytesTotal = 100))
        assertEquals(100, roundedTrayProgressPercent(bytesDone = 100, bytesTotal = 100))
    }

    @Test
    fun `progress is zero for an unknown total`() {
        assertEquals(0, roundedTrayProgressPercent(bytesDone = 0, bytesTotal = 0))
    }
}
