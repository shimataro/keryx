package works.merc.keryx.app.tray

import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.messages.ExportedObject
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the D-Bus wire signatures of the exported objects.
 *
 * `ExportedObject` generates the same introspection XML dbus-java would publish on a real
 * bus, and its constructor and `getIntrospectiondata()` are both public - so a wrong
 * signature fails here, on every CI runner, instead of only showing up as a misbehaving
 * icon on a KDE machine.
 */
class DBusSignatureTest {
    private fun introspect(item: Any): String =
        ExportedObject(item as org.freedesktop.dbus.interfaces.DBusInterface, false).introspectiondata

    private fun statusNotifierItem() = SniStatusNotifierItem(
        objectPath = SniConnection.ITEM_PATH,
        menuPath = SniConnection.MENU_PATH,
        onNewIcon = {},
        onNewToolTip = {},
    )

    private fun dbusMenu() = SniDBusMenu(
        objectPath = SniConnection.MENU_PATH,
        initialState = TrayMenuState("表示", "終了"),
        onLayoutUpdated = {},
    )

    private fun assertContains(haystack: String, needle: String) {
        assertTrue(needle in haystack, "expected introspection data to contain:\n  $needle\ngot:\n$haystack")
    }

    // --- org.kde.StatusNotifierItem ---

    @Test
    fun `status notifier item declares the expected interface and methods`() {
        val xml = introspect(statusNotifierItem())

        assertContains(xml, """<interface name="org.kde.StatusNotifierItem">""")
        assertContains(xml, """<method name="Activate" >""")
        assertContains(xml, """<method name="SecondaryActivate" >""")
        assertContains(xml, """<method name="ContextMenu" >""")
        assertContains(xml, """<method name="Scroll" >""")
    }

    @Test
    fun `status notifier item methods take the argument types hosts send`() {
        val xml = introspect(statusNotifierItem())

        val activate = xml.substringAfter("""<method name="Activate" >""").substringBefore("</method>")
        assertEquals(2, Regex("""type="i"""").findAll(activate).count(), "Activate(ii)")

        val scroll = xml.substringAfter("""<method name="Scroll" >""").substringBefore("</method>")
        assertContains(scroll, """type="i"""")
        assertContains(scroll, """type="s"""")
    }

    @Test
    fun `status notifier item exposes the properties a host reads`() {
        // GetAll is what hosts actually call; assert the concrete D-Bus types of the values.
        val properties = statusNotifierItem().GetAll(SNI_INTERFACE)

        assertEquals("s", properties.getValue("Category").sig)
        assertEquals("s", properties.getValue("Id").sig)
        assertEquals("s", properties.getValue("Title").sig)
        assertEquals("s", properties.getValue("Status").sig)
        assertEquals("i", properties.getValue("WindowId").sig)
        assertEquals("a(iiay)", properties.getValue("IconPixmap").sig)
        assertEquals("a(iiay)", properties.getValue("OverlayIconPixmap").sig)
        assertEquals("a(iiay)", properties.getValue("AttentionIconPixmap").sig)
        assertEquals("(sa(iiay)ss)", properties.getValue("ToolTip").sig)
        assertEquals("b", properties.getValue("ItemIsMenu").sig)
        assertEquals("o", properties.getValue("Menu").sig)
    }

    @Test
    fun `status notifier item reports ItemIsMenu false so a primary click reaches Activate`() {
        assertEquals(false, statusNotifierItem().GetAll(SNI_INTERFACE).getValue("ItemIsMenu").value)
    }

    @Test
    fun `status notifier item points at the exported menu path`() {
        assertEquals(
            SniConnection.MENU_PATH,
            statusNotifierItem().GetAll(SNI_INTERFACE).getValue("Menu").value.toString(),
        )
    }

    @Test
    fun `status notifier item serves no properties for a foreign interface`() {
        assertTrue(statusNotifierItem().GetAll("org.example.Other").isEmpty())
    }

    @Test
    fun `status notifier item serves a known property through Get`() {
        val item = statusNotifierItem()

        // Compare the unwrapped values: Variant.equals ignores the signature.
        assertEquals(
            item.GetAll(SNI_INTERFACE).getValue("Id").value,
            item.Get<Variant<*>>(SNI_INTERFACE, "Id").value,
        )
    }

    @Test
    fun `status notifier item rejects an unknown property`() {
        // A null return cannot be marshalled: dbus-java would answer the host's probe with an
        // NPE-derived error instead of an unknown-property one.
        assertFailsWith<UnknownProperty> {
            statusNotifierItem().Get<Variant<*>>(SNI_INTERFACE, "XAyatanaLabel")
        }
    }

    @Test
    fun `status notifier item rejects a property on a foreign interface`() {
        assertFailsWith<UnknownProperty> {
            statusNotifierItem().Get<Variant<*>>("org.example.Other", "Category")
        }
    }

    // --- com.canonical.dbusmenu ---

    @Test
    fun `dbusmenu declares the expected interface and methods`() {
        val xml = introspect(dbusMenu())

        assertContains(xml, """<interface name="com.canonical.dbusmenu">""")
        listOf("GetLayout", "GetGroupProperties", "GetProperty", "Event", "EventGroup", "AboutToShow", "AboutToShowGroup")
            .forEach { assertContains(xml, """<method name="$it" >""") }
    }

    @Test
    fun `dbusmenu method signatures match the spec`() {
        val xml = introspect(dbusMenu())

        fun method(name: String) = xml.substringAfter("""<method name="$name" >""").substringBefore("</method>")

        val getLayout = method("GetLayout")
        assertContains(getLayout, """type="u" direction="out"""")
        assertContains(getLayout, """type="(ia{sv}av)" direction="out"""")
        assertContains(getLayout, """type="as" direction="in"""")

        assertContains(method("GetGroupProperties"), """type="a(ia{sv})" direction="out"""")
        assertContains(method("GetGroupProperties"), """type="ai" direction="in"""")
        assertContains(method("GetProperty"), """type="v" direction="out"""")

        val event = method("Event")
        assertContains(event, """type="v" direction="in"""")
        assertContains(event, """type="u" direction="in"""")

        assertContains(method("EventGroup"), """type="a(isvu)" direction="in"""")
        assertContains(method("EventGroup"), """type="ai" direction="out"""")
        assertContains(method("AboutToShow"), """type="b" direction="out"""")
        assertEquals(
            2,
            Regex("""type="ai" direction="out"""").findAll(method("AboutToShowGroup")).count(),
            "AboutToShowGroup returns (ai, ai)",
        )
    }

    @Test
    fun `dbusmenu declares LayoutUpdated with a revision and a parent id`() {
        val signal = introspect(dbusMenu())
            .substringAfter("""<signal name="LayoutUpdated">""")
            .substringBefore("</signal>")

        assertContains(signal, """type="u" direction="out"""")
        assertContains(signal, """type="i" direction="out"""")
    }

    @Test
    fun `dbusmenu declares ItemsPropertiesUpdated with the updated and removed payloads`() {
        // A wildcard type argument marshals to the malformed `a` without throwing (dbus-java's
        // Marshalling has no WildcardType branch), so pin the element signatures explicitly.
        val xml = introspect(dbusMenu())
        assertContains(xml, """<signal name="ItemsPropertiesUpdated">""")

        val signal = xml
            .substringAfter("""<signal name="ItemsPropertiesUpdated">""")
            .substringBefore("</signal>")

        assertEquals(
            listOf("a(ia{sv})", "a(ias)"),
            Regex("""type="([^"]*)" direction="out"""").findAll(signal).map { it.groupValues[1] }.toList(),
            "ItemsPropertiesUpdated(a(ia{sv}), a(ias))",
        )
    }

    @Test
    fun `dbusmenu properties use the types the spec requires`() {
        val properties = dbusMenu().GetAll(DBUSMENU_INTERFACE)

        assertEquals("u", properties.getValue("Version").sig)
        assertEquals(3, (properties.getValue("Version").value as org.freedesktop.dbus.types.UInt32).toInt())
        assertEquals("s", properties.getValue("TextDirection").sig)
        assertEquals("s", properties.getValue("Status").sig)
        assertEquals("as", properties.getValue("IconThemePath").sig)
    }

    @Test
    fun `dbusmenu rejects an unknown property`() {
        assertFailsWith<UnknownProperty> {
            dbusMenu().Get<Variant<*>>(DBUSMENU_INTERFACE, "NoSuchProperty")
        }
    }

    // --- struct / method type mapping ---

    @Test
    fun `structs map to the expected D-Bus signatures`() {
        assertEquals("(iiay)", Marshalling.getDBusType(SniPixmap::class.java).single())
        assertEquals("(sa(iiay)ss)", Marshalling.getDBusType(SniToolTip::class.java).single())
        assertEquals("(ia{sv}av)", Marshalling.getDBusType(DBusMenuLayoutItem::class.java).single())
        assertEquals("(ia{sv})", Marshalling.getDBusType(DBusMenuItemProperties::class.java).single())
        assertEquals("(ias)", Marshalling.getDBusType(DBusMenuRemovedProperties::class.java).single())
        assertEquals("(isvu)", Marshalling.getDBusType(DBusMenuEventEntry::class.java).single())
        assertEquals("(iiibiiay)", Marshalling.getDBusType(NotificationImageData::class.java).single())
    }

    @Test
    fun `Notify takes the freedesktop notification signature`() {
        val notify = FreedesktopNotifications::class.java.declaredMethods.single { it.name == "Notify" }
        assertEquals("susssasa{sv}i", Marshalling.getDBusType(notify.genericParameterTypes))
        assertEquals("u", Marshalling.getDBusType(notify.genericReturnType).single())
    }

    @Test
    fun `NotificationClosed carries id and reason`() {
        val ctor = FreedesktopNotifications.NotificationClosed::class.java.declaredConstructors.single()
        assertEquals("uu", Marshalling.getDBusType(ctor.genericParameterTypes.drop(1).toTypedArray()))
    }
}
