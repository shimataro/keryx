package works.merc.keryx.app.tray

import org.freedesktop.dbus.errors.UnknownProperty
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
internal fun <A> Map<String, Variant<*>>.propertyOrThrow(interfaceName: String, propertyName: String): A =
    (this[propertyName] ?: throw UnknownProperty("$interfaceName.$propertyName is not available")) as A
