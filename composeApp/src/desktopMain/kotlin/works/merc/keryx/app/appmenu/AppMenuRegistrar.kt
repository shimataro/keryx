package works.merc.keryx.app.appmenu

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32

/**
 * `com.canonical.AppMenu.Registrar` — KDE's kded service an app registers its exported menu with.
 *
 * Method names intentionally use the exact wire names (PascalCase) rather than idiomatic Kotlin
 * names plus `@DBusMemberName`, matching `tray/SniInterfaces.kt`: dbus-java keys both its
 * introspection XML and its dispatch off the member name, and matching the wire spelling removes
 * any doubt about the mapping.
 *
 * The app never sets `_KDE_NET_WM_APPMENU_SERVICE_NAME` / `_KDE_NET_WM_APPMENU_OBJECT_PATH` itself —
 * the registrar writes those X11 properties in response to [RegisterWindow]. The registrar infers
 * the D-Bus service name from the caller's unique bus name, so no well-known name has to be claimed.
 */
@DBusInterfaceName("com.canonical.AppMenu.Registrar")
internal interface AppMenuRegistrar : DBusInterface {
    /**
     * Associates a window with the dbusmenu object exported at [menuObjectPath] on this connection.
     *
     * @param windowId The X11 window id (XID) of the window.
     * @param menuObjectPath The object path of the exported `com.canonical.dbusmenu` object.
     */
    fun RegisterWindow(windowId: UInt32, menuObjectPath: DBusPath)

    /**
     * Removes the association for [windowId].
     *
     * @param windowId The X11 window id (XID) previously registered.
     */
    fun UnregisterWindow(windowId: UInt32)

    /**
     * Declared for introspection completeness; Keryx never calls it. Returns the service name and
     * menu object path currently registered for [windowId].
     *
     * @param windowId The X11 window id (XID) to look up.
     * @return The registered service name and menu object path.
     */
    fun GetMenuForWindow(windowId: UInt32): GetMenuForWindowReply
}

/**
 * `GetMenuForWindow`'s reply: `s` service name + `o` menu object path.
 *
 * A `Tuple` subclass modelling multiple out-arguments; it must be **generic** because
 * `ExportedObject.generateMethodsXml` casts a Tuple-returning method's generic return type to a
 * `ParameterizedType` and derives one out-arg per actual type argument (see `tray/SniTypes.kt`).
 */
internal class GetMenuForWindowResult<S, O>(
    @Position(0) @JvmField val service: S,
    @Position(1) @JvmField val menuObjectPath: O,
) : Tuple()

/** [GetMenuForWindowResult] as `GetMenuForWindow` returns it. */
internal typealias GetMenuForWindowReply = GetMenuForWindowResult<String, DBusPath>
