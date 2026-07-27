package works.merc.keryx.app.tray

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/*
 * D-Bus interfaces for the Linux tray.
 *
 * Method names intentionally use the exact wire names (PascalCase) rather than idiomatic
 * Kotlin names plus @DBusMemberName: dbus-java keys both its introspection XML and its
 * incoming-call dispatch off the member name, and matching the wire spelling removes any
 * doubt about the mapping. `org.freedesktop.dbus.interfaces.Properties` - which the exported
 * objects implement - is spelled the same way in the library itself.
 *
 * Collection parameters and return types carry @JvmSuppressWildcards: Kotlin's `List<T>` is
 * covariant, so without it the generated Java signature would be `List<? extends T>` and
 * dbus-java's reflective signature derivation would see a wildcard instead of the element
 * type.
 */

/** `org.kde.StatusNotifierItem` - the tray item itself. */
@DBusInterfaceName("org.kde.StatusNotifierItem")
internal interface StatusNotifierItem : DBusInterface {
    /** Primary click. Requires `ItemIsMenu = false`, otherwise hosts open the menu instead. */
    fun Activate(x: Int, y: Int)

    /** Middle click. */
    fun SecondaryActivate(x: Int, y: Int)

    /** Only called by hosts that do not render the `Menu` object themselves. */
    fun ContextMenu(x: Int, y: Int)

    /** Wheel over the icon. Must exist or hosts log an UnknownMethod error per scroll tick. */
    fun Scroll(delta: Int, orientation: String)

    class NewIcon(path: String) : DBusSignal(path)

    class NewToolTip(path: String) : DBusSignal(path)

    /** Declared for introspection completeness; Keryx's title never changes. */
    class NewTitle(path: String) : DBusSignal(path)

    /** Declared for introspection completeness; Keryx is always `Active`. */
    class NewStatus(path: String, status: String) : DBusSignal(path, status)
}

/** `org.kde.StatusNotifierWatcher` - the host-side registry we register with. */
@DBusInterfaceName("org.kde.StatusNotifierWatcher")
internal interface StatusNotifierWatcher : DBusInterface {
    /**
     * Registers a status notifier item by its **well-known bus name** (not the unique `:1.x`
     * name and not an object path). The name must already be owned by the caller: KDE's
     * watcher checks `isServiceRegistered` and silently drops the item otherwise.
     */
    fun RegisterStatusNotifierItem(service: String)
}

/** `com.canonical.dbusmenu` - the menu the host renders on right-click. */
@DBusInterfaceName("com.canonical.dbusmenu")
internal interface DBusMenu : DBusInterface {
    fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<@JvmSuppressWildcards String>,
    ): MenuLayoutReply

    fun GetGroupProperties(
        ids: List<@JvmSuppressWildcards Int>,
        propertyNames: List<@JvmSuppressWildcards String>,
    ): List<@JvmSuppressWildcards DBusMenuItemProperties>

    fun GetProperty(id: Int, name: String): Variant<*>

    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    fun EventGroup(events: List<@JvmSuppressWildcards DBusMenuEventEntry>): List<@JvmSuppressWildcards Int>

    fun AboutToShow(id: Int): Boolean

    fun AboutToShowGroup(ids: List<@JvmSuppressWildcards Int>): AboutToShowGroupReply

    class LayoutUpdated(path: String, revision: UInt32, parent: Int) : DBusSignal(path, revision, parent)

    /**
     * Declared for introspection completeness. Keryx only ever emits [LayoutUpdated]: hosts
     * implement it far more consistently, and re-fetching a two-item layout costs nothing.
     */
    class ItemsPropertiesUpdated(
        path: String,
        updated: List<@JvmSuppressWildcards DBusMenuItemProperties>,
        removed: List<@JvmSuppressWildcards DBusMenuRemovedProperties>,
    ) : DBusSignal(path, updated, removed)
}

/** `org.freedesktop.Notifications` - the desktop notification daemon. */
@DBusInterfaceName("org.freedesktop.Notifications")
internal interface FreedesktopNotifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<@JvmSuppressWildcards String>,
        hints: Map<String, @JvmSuppressWildcards Variant<*>>,
        expireTimeout: Int,
    ): UInt32
}
