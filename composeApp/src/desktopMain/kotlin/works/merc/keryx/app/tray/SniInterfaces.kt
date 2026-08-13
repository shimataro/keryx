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
    /**
 * Handles a primary click at the specified screen coordinates.
 *
 * @param x The horizontal screen coordinate.
 * @param y The vertical screen coordinate.
 */
    fun Activate(x: Int, y: Int)

    /** Middle click. */
    fun SecondaryActivate(x: Int, y: Int)

    /**
 * Handles activation of the context menu at the specified screen coordinates.
 *
 * @param x The horizontal screen coordinate.
 * @param y The vertical screen coordinate.
 */
    fun ContextMenu(x: Int, y: Int)

    /**
 * Handles a scroll event over the tray item.
 *
 * @param delta The scroll amount.
 * @param orientation The scroll orientation.
 */
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
    /**
     * Retrieves the menu layout beneath a parent item.
     *
     * @param parentId The identifier of the parent menu item.
     * @param recursionDepth The depth of child items to include.
     * @param propertyNames The item properties to include in the layout.
     * @return The requested menu layout.
     */
    fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<@JvmSuppressWildcards String>,
    ): MenuLayoutReply

    /**
     * Retrieves selected properties for a group of menu items.
     *
     * @param ids The identifiers of the menu items.
     * @param propertyNames The names of the properties to retrieve.
     * @return The requested properties for each menu item.
     */
    fun GetGroupProperties(
        ids: List<@JvmSuppressWildcards Int>,
        propertyNames: List<@JvmSuppressWildcards String>,
    ): List<@JvmSuppressWildcards DBusMenuItemProperties>

    /**
 * Retrieves a named property for a menu item.
 *
 * @param id The menu item identifier.
 * @param name The property name.
 * @return The property's value.
 */
fun GetProperty(id: Int, name: String): Variant<*>

    /**
 * Dispatches an event for a menu item.
 *
 * @param id The identifier of the menu item associated with the event.
 * @param eventId The event type.
 * @param data The event payload.
 * @param timestamp The event timestamp.
 */
fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)

    /**
 * Sends multiple menu events as a group.
 *
 * @param events The menu events to send.
 * @return The identifiers resulting from processing the events.
 */
fun EventGroup(events: List<@JvmSuppressWildcards DBusMenuEventEntry>): List<@JvmSuppressWildcards Int>

    /**
 * Determines whether a menu item requires its contents to be refreshed before display.
 *
 * @param id The identifier of the menu item.
 * @return `true` if the menu item contents have changed, `false` otherwise.
 */
fun AboutToShow(id: Int): Boolean

    /**
 * Prepares a group of menu items for display.
 *
 * @param ids The identifiers of the menu items to prepare.
 * @return The result of preparing the specified menu items.
 */
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
    /**
     * Sends a desktop notification.
     *
     * @param appName The application name associated with the notification.
     * @param replacesId The identifier of an existing notification to replace, or `0` to create a new notification.
     * @param appIcon The icon identifier for the notification.
     * @param summary The notification title.
     * @param body The notification body.
     * @param actions The actions available for the notification.
     * @param hints Additional notification hints.
     * @param expireTimeout The expiration timeout in milliseconds.
     * @return The identifier assigned to the notification.
     */
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

    /**
     * Emitted by the notification daemon when the user invokes an action - including the
     * conventional `"default"` key, which most daemons invoke on a click of the notification
     * body itself rather than a rendered button. Unscoped by sender, so this also fires for
     * every other application's notifications; callers must filter by the [id] returned from
     * their own [Notify] call.
     */
    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)

    /**
     * Emitted by the notification daemon when a notification is closed for any reason (expired,
     * dismissed, or closed via `CloseNotification`) - the id becomes invalid afterward regardless
     * of which. Unscoped by sender, like [ActionInvoked]; callers filter by their own ids.
     */
    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
}
