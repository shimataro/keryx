package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dbusmenu property helpers and constants in `SniDBusMenu.kt`. */
class SniDBusMenuPropertiesTest {

    private fun Map<String, Variant<*>>.plain() = mapValues { it.value.value }

    @Test
    fun `constants match the dbusmenu protocol`() {
        assertEquals("com.canonical.dbusmenu", DBUSMENU_INTERFACE)
        assertEquals(0, DBUSMENU_ROOT_ID)
        assertEquals("(ia{sv}av)", DBUSMENU_LAYOUT_SIGNATURE)
        assertEquals("clicked", DBUSMENU_EVENT_CLICKED)
    }

    @Test
    fun `root properties only display children as a submenu`() {
        assertEquals(mapOf("children-display" to "submenu"), dbusMenuRootProperties().plain())
    }

    @Test
    fun `leaf properties are in the golden key order with the label escaped`() {
        val props = dbusMenuLeafProperties("Show_Hide")

        assertEquals(listOf("type", "label", "enabled", "visible"), props.keys.toList())
        assertEquals(listOf<Any?>("standard", "Show__Hide", true, true), props.values.map { it.value })
    }

    @Test
    fun `leaf properties report a disabled item`() {
        assertEquals(false, dbusMenuLeafProperties("Update", enabled = false)["enabled"]?.value)
    }

    @Test
    fun `separator properties are in the golden key order`() {
        val props = dbusMenuSeparatorProperties()

        assertEquals(listOf("type", "visible"), props.keys.toList())
        assertEquals(listOf<Any?>("separator", true), props.values.map { it.value })
    }

    @Test
    fun `filtering with no names returns every property`() {
        val all = dbusMenuLeafProperties("X")

        assertEquals(all, filterDBusMenuProperties(all, emptyList()))
    }

    @Test
    fun `filtering keeps only the requested names and ignores unknown ones`() {
        val all = dbusMenuLeafProperties("X")

        val filtered = filterDBusMenuProperties(all, listOf("label", "enabled", "nonexistent"))

        assertEquals(setOf("label", "enabled"), filtered.keys)
    }

    @Test
    fun `interface properties are fixed for the dbusmenu interface`() {
        val props = dbusMenuProperties(DBUSMENU_INTERFACE)

        assertEquals(setOf("Version", "TextDirection", "Status", "IconThemePath"), props.keys)
        assertEquals(UInt32(3L), props["Version"]?.value)
        assertEquals("ltr", props["TextDirection"]?.value)
        assertEquals("normal", props["Status"]?.value)
        assertEquals(emptyList<String>(), props["IconThemePath"]?.value)
    }

    @Test
    fun `any other interface has no properties`() {
        assertTrue(dbusMenuProperties("org.example.Other").isEmpty())
    }

    @Test
    fun `about-to-show reply lists known ids as updates only when stale`() {
        val known = setOf(0, 1, 2)

        val stale = aboutToShowGroupReply(listOf(1, 2, 9), stale = true, knownIds = known)
        val fresh = aboutToShowGroupReply(listOf(1, 2, 9), stale = false, knownIds = known)

        assertEquals(listOf(1, 2), stale.updatesNeeded)
        assertEquals(listOf(9), stale.idErrors)
        assertTrue(fresh.updatesNeeded.isEmpty())
        assertEquals(listOf(9), fresh.idErrors)
    }
}
