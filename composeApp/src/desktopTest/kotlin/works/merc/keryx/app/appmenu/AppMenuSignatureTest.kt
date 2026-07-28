package works.merc.keryx.app.appmenu

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.ExportedObject
import org.freedesktop.dbus.types.UInt32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the D-Bus wire signatures of `com.canonical.AppMenu.Registrar` (the params we send) and of
 * the exported `com.canonical.dbusmenu` object — same `ExportedObject` technique as the tray's
 * `DBusSignatureTest`, so a wrong signature fails on every CI runner rather than only misbehaving on
 * a KDE machine.
 */
class AppMenuSignatureTest {

    /** A no-op registrar just so `ExportedObject` can generate the introspection XML for its methods. */
    private class FakeRegistrar : AppMenuRegistrar {
        override fun getObjectPath(): String = "/com/canonical/AppMenu/Registrar"
        override fun RegisterWindow(windowId: UInt32, menuObjectPath: DBusPath) = Unit
        override fun UnregisterWindow(windowId: UInt32) = Unit
        override fun GetMenuForWindow(windowId: UInt32): GetMenuForWindowReply =
            GetMenuForWindowResult("", DBusPath("/"))
    }

    private fun introspect(obj: DBusInterface): String = ExportedObject(obj, false).introspectiondata

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(needle in haystack, "expected introspection data to contain:\n  $needle\ngot:\n$haystack")
    }

    @Test
    fun `registrar declares the expected interface and methods`() {
        val xml = introspect(FakeRegistrar())

        assertContains(xml, """<interface name="com.canonical.AppMenu.Registrar">""")
        listOf("RegisterWindow", "UnregisterWindow", "GetMenuForWindow")
            .forEach { assertContains(xml, """<method name="$it" >""") }
    }

    @Test
    fun `registerWindow takes a uint window id and an object path`() {
        val xml = introspect(FakeRegistrar())
        val method = xml.substringAfter("""<method name="RegisterWindow" >""").substringBefore("</method>")
        assertContains(method, """type="u" direction="in"""")
        assertContains(method, """type="o" direction="in"""")
    }

    @Test
    fun `unregisterWindow takes a uint window id`() {
        val xml = introspect(FakeRegistrar())
        val method = xml.substringAfter("""<method name="UnregisterWindow" >""").substringBefore("</method>")
        assertContains(method, """type="u" direction="in"""")
    }

    @Test
    fun `getMenuForWindow returns a service name and an object path`() {
        val xml = introspect(FakeRegistrar())
        val method = xml.substringAfter("""<method name="GetMenuForWindow" >""").substringBefore("</method>")
        assertContains(method, """type="u" direction="in"""")
        assertContains(method, """type="s" direction="out"""")
        assertContains(method, """type="o" direction="out"""")
    }

    @Test
    fun `registerWindow parameter types marshal to uo`() {
        val method = AppMenuRegistrar::class.java.declaredMethods.single { it.name == "RegisterWindow" }
        assertEquals("uo", Marshalling.getDBusType(method.genericParameterTypes))
    }

    @Test
    fun `the exported menu declares the com canonical dbusmenu interface`() {
        val menu = AppMenuDBusMenu(objectPath = AppMenuConnection.MENU_PATH, onLayoutUpdated = {})
        val xml = introspect(menu)

        assertContains(xml, """<interface name="com.canonical.dbusmenu">""")
        listOf("GetLayout", "GetGroupProperties", "GetProperty", "Event", "EventGroup", "AboutToShow", "AboutToShowGroup")
            .forEach { assertContains(xml, """<method name="$it" >""") }
    }
}
