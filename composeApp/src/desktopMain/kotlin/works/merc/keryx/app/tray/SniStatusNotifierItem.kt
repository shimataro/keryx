package works.merc.keryx.app.tray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.errors.PropertyReadOnly
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

    override fun Activate(x: Int, y: Int) {
        _activations.tryEmit(Unit)
    }

    override fun SecondaryActivate(x: Int, y: Int) {
        _activations.tryEmit(Unit)
    }

    override fun ContextMenu(x: Int, y: Int) {
        // Hosts that read the `Menu` property render the dbusmenu themselves and never call
        // this; implementing it keeps the others from logging an UnknownMethod error.
        Log.debug(LOG_TAG, "ContextMenu requested by the host; the dbusmenu object handles it")
    }

    override fun Scroll(delta: Int, orientation: String) {
        // Keryx has nothing to scroll, but the method must exist (see the interface).
    }

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

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName)[propertyName] as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        throw PropertyReadOnly("$interfaceName.$propertyName is read-only")
    }
}
