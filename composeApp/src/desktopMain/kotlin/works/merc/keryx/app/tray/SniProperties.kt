package works.merc.keryx.app.tray

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

/**
 * Looks up one property of [interfaceName] in the map `Properties.GetAll` serves, rejecting a
 * miss the way the D-Bus spec requires.
 *
 * dbus-java has no null handling on the reply path: a `null` from an exported method skips
 * `Marshalling.convertParameters`' conversion and reaches `Message.appendOne`, whose VARIANT
 * branch dereferences it. The resulting NPE is answered as a generic `DBusExecutionException`,
 * so the lookup has to reject the miss itself. Shared by the two exported objects so their
 * error behaviour cannot drift apart.
 */
@Suppress("UNCHECKED_CAST")
/**
     * Retrieves a D-Bus property value or reports that the property is unavailable.
     *
     * @param interfaceName The D-Bus interface containing the property.
     * @param propertyName The name of the property to retrieve.
     * @return The property value cast to the requested type.
     * @throws UnknownProperty If the property is unavailable.
     */
    internal fun <A> Map<String, Variant<*>>.propertyOrThrow(interfaceName: String, propertyName: String): A =
    (this[propertyName] ?: throw UnknownProperty("$interfaceName.$propertyName is not available")) as A

/**
 * [Properties] for objects whose properties are entirely read-only and fully enumerable via
 * [GetAll] — the shape every exported StatusNotifierItem/dbusmenu object here has. Implementing
 * this instead of [Properties] directly only requires [GetAll]: [Get] delegates to it via
 * [propertyOrThrow], and [Set] always rejects, per the D-Bus `Properties.Set` contract for a
 * property that isn't writable.
 *
 * Annotations aren't inherited across Java/Kotlin interfaces, so [Properties]'s own
 * `@DBusInterfaceName("org.freedesktop.DBus.Properties")` must be repeated here — otherwise
 * dbus-java's introspection would advertise this interface under its Kotlin-qualified name
 * instead of merging it with the standard `org.freedesktop.DBus.Properties` entry.
 */
@DBusInterfaceName("org.freedesktop.DBus.Properties")
internal interface ReadOnlyDBusProperties : Properties {
    /**
         * Retrieves a property value from the specified interface.
         *
         * @param interfaceName The D-Bus interface containing the property.
         * @param propertyName The name of the property to retrieve.
         * @return The property's value.
         */
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName).propertyOrThrow(interfaceName, propertyName)

    /**
     * Rejects attempts to modify a read-only D-Bus property.
     *
     * @param interfaceName The D-Bus interface containing the property.
     * @param propertyName The property to modify.
     * @param value The requested property value.
     * @throws PropertyReadOnly Always, because the property is read-only.
     */
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        throw PropertyReadOnly("$interfaceName.$propertyName is read-only")
    }
}
