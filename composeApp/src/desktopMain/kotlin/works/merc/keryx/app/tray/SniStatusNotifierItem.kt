package works.merc.keryx.app.tray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.core.Log
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "SniItem"

/** The interface name our properties live under. */
internal const val SNI_INTERFACE = "org.kde.StatusNotifierItem"

/**
 * The `/StatusNotifierItem` object exported on the session bus.
 *
 * Deliberately holds no `DBusConnection`: signal emission is injected as [onNewIcon] /
 * [onNewToolTip] callbacks, so the whole object can be constructed and exercised in tests
 * without a bus.
 *
 * Every method here is invoked on a dbus-java worker thread and must return promptly - a
 * blocking exported method stalls that worker. State changes therefore only touch atomics,
 * and host-initiated actions are published through [activations], which `LinuxTray` collects
 * on the UI thread.
 */
internal class SniStatusNotifierItem(
    private val objectPath: String,
    private val menuPath: String,
    private val onNewIcon: () -> Unit,
    private val onNewToolTip: () -> Unit,
) : StatusNotifierItem, Properties {

    private val _activations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted on primary/middle click. Coalesces a click storm into a single toggle. */
    val activations: SharedFlow<Unit> = _activations

    private val iconPixmaps = AtomicReference<List<SniPixmap>>(emptyList())
    private val toolTipTitle = AtomicReference("Keryx")

    /**
 * Gets the DBus object path for this item.
 *
 * @return The DBus object path.
 */
override fun getObjectPath(): String = objectPath

    /** Replaces the published icon and tells the host to re-read it. */
    fun updateIcon(pixmaps: List<SniPixmap>) {
        iconPixmaps.set(pixmaps)
        onNewIcon()
    }

    /** Replaces the tooltip title and tells the host to re-read it. */
    fun updateToolTip(title: String) {
        toolTipTitle.set(title)
        onNewToolTip()
    }

    /**
     * Publishes an activation event for a primary or middle click.
     */
    override fun Activate(x: Int, y: Int) {
        _activations.tryEmit(Unit)
    }

    /**
     * Publishes an activation event for a secondary click.
     */
    override fun SecondaryActivate(x: Int, y: Int) {
        _activations.tryEmit(Unit)
    }

    /**
     * Handles a host context-menu request through the exported menu object.
     *
     * @param x The horizontal click coordinate.
     * @param y The vertical click coordinate.
     */
    override fun ContextMenu(x: Int, y: Int) {
        // Hosts that read the `Menu` property render the dbusmenu themselves and never call
        // this; implementing it keeps the others from logging an UnknownMethod error.
        Log.debug(LOG_TAG, "ContextMenu requested by the host; the dbusmenu object handles it")
    }

    /**
     * Handles a scroll request without changing application state.
     *
     * @param delta The scroll amount.
     * @param orientation The scroll direction.
     */
    override fun Scroll(delta: Int, orientation: String) {
        // Keryx has nothing to scroll, but the method must exist (see the interface).
    }

    /**
     * Provides the DBus properties for the requested StatusNotifierItem interface.
     *
     * @param interfaceName The DBus interface whose properties are requested.
     * @return The interface properties, or an empty map when the interface is unsupported.
     */
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != SNI_INTERFACE) return emptyMap()
        // Serve every property even when empty: hosts build a proxy from this map and some
        // of them bail out on a missing entry.
        return mapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("keryx"),
            "Title" to Variant("Keryx"),
            "Status" to Variant("Active"),
            "WindowId" to Variant(0),
            "IconName" to Variant(""),
            "IconThemePath" to Variant(""),
            "IconPixmap" to Variant(iconPixmaps.get(), "a(iiay)"),
            "OverlayIconName" to Variant(""),
            "OverlayIconPixmap" to Variant(emptyList<SniPixmap>(), "a(iiay)"),
            "AttentionIconName" to Variant(""),
            "AttentionIconPixmap" to Variant(emptyList<SniPixmap>(), "a(iiay)"),
            "AttentionMovieName" to Variant(""),
            "ToolTip" to Variant(
                SniToolTip("", emptyList(), toolTipTitle.get(), ""),
                "(sa(iiay)ss)",
            ),
            // false is what makes a primary click reach Activate() instead of opening the menu.
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(menuPath)),
        )
    }

    /**
     * Retrieves a property value from the specified StatusNotifierItem interface.
     *
     * @param interfaceName The DBus interface containing the property.
     * @param propertyName The name of the property to retrieve.
     * @return The property value cast to the requested type.
     * @throws UnknownProperty If the interface or the property is not served here.
     */
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName).propertyOrThrow(interfaceName, propertyName)

    /**
     * Rejects attempts to modify a StatusNotifierItem property.
     *
     * @param interfaceName The DBus interface containing the property.
     * @param propertyName The property being modified.
     * @param value The requested property value.
     * @throws PropertyReadOnly Always, because the property is read-only.
     */
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        throw PropertyReadOnly("$interfaceName.$propertyName is read-only")
    }
}
