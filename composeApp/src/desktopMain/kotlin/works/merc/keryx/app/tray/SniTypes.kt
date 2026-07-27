package works.merc.keryx.app.tray

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/*
 * D-Bus structures for org.kde.StatusNotifierItem, com.canonical.dbusmenu and
 * org.freedesktop.Notifications.
 *
 * Fields are `@JvmField` on purpose: dbus-java's `Container.setup()` walks
 * `getDeclaredFields()`, calls `setAccessible(true)` and reads the ones annotated with
 * `@Position`, so plain public fields keep that reflection trivial. Fields without
 * `@Position` (such as the Compose compiler's synthetic `$stable`) are skipped.
 *
 * Do not give these classes default parameter values: dbus-java constructs incoming
 * structs through the single declared constructor, and extra synthetic overloads would
 * make that lookup ambiguous.
 */

/** `(iiay)` - width, height, ARGB32 pixel data in network (big-endian) byte order. */
internal class SniPixmap(
    @Position(0) @JvmField val width: Int,
    @Position(1) @JvmField val height: Int,
    @Position(2) @JvmField val data: ByteArray,
) : Struct()

/** `(sa(iiay)ss)` - icon name, icon pixmaps, title, description. */
internal class SniToolTip(
    @Position(0) @JvmField val iconName: String,
    @Position(1) @JvmField val iconPixmap: List<SniPixmap>,
    @Position(2) @JvmField val title: String,
    @Position(3) @JvmField val description: String,
) : Struct()

/** `(ia{sv}av)` - a dbusmenu node: id, properties, children (each wrapped in a variant). */
internal class DBusMenuLayoutItem(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

/** `(ia{sv})` - one entry of `GetGroupProperties`' reply. */
internal class DBusMenuItemProperties(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

/** `(ias)` - one entry of `ItemsPropertiesUpdated`' removed-properties argument. */
internal class DBusMenuRemovedProperties(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val properties: List<String>,
) : Struct()

/** `(isvu)` - one entry of `EventGroup`'s argument. */
internal class DBusMenuEventEntry(
    @Position(0) @JvmField val id: Int,
    @Position(1) @JvmField val eventId: String,
    @Position(2) @JvmField val data: Variant<*>,
    @Position(3) @JvmField val timestamp: UInt32,
) : Struct()

/*
 * Multiple out-arguments are modelled as `Tuple` subclasses, and those must be **generic**:
 * `ExportedObject.generateMethodsXml` casts a Tuple-returning method's generic return type to
 * `ParameterizedType` and derives one out-arg per actual type argument. A non-generic subclass
 * throws ClassCastException at export time.
 */

/** `GetLayout`'s reply: `u` revision + `(ia{sv}av)` layout. */
internal class GetLayoutResult<R, L>(
    @Position(0) @JvmField val revision: R,
    @Position(1) @JvmField val layout: L,
) : Tuple()

/** `AboutToShowGroup`'s reply: `ai` ids needing an update + `ai` unknown ids. */
internal class AboutToShowGroupResult<U, E>(
    @Position(0) @JvmField val updatesNeeded: U,
    @Position(1) @JvmField val idErrors: E,
) : Tuple()

/** [GetLayoutResult] as `GetLayout` returns it. */
internal typealias MenuLayoutReply = GetLayoutResult<UInt32, DBusMenuLayoutItem>

/** [AboutToShowGroupResult] as `AboutToShowGroup` returns it. */
internal typealias AboutToShowGroupReply =
    AboutToShowGroupResult<List<@JvmSuppressWildcards Int>, List<@JvmSuppressWildcards Int>>

/**
 * `(iiibiiay)` - the `image-data` hint of `org.freedesktop.Notifications.Notify`:
 * width, height, row stride, has-alpha, bits per sample, channels, pixel data.
 *
 * Note the pixel layout is **RGBA, row-major** here - not the big-endian ARGB32 that
 * [SniPixmap] uses. The two encoders must not be confused; see `TrayPixmapTest`.
 */
internal class NotificationImageData(
    @Position(0) @JvmField val width: Int,
    @Position(1) @JvmField val height: Int,
    @Position(2) @JvmField val rowStride: Int,
    @Position(3) @JvmField val hasAlpha: Boolean,
    @Position(4) @JvmField val bitsPerSample: Int,
    @Position(5) @JvmField val channels: Int,
    @Position(6) @JvmField val data: ByteArray,
) : Struct()
