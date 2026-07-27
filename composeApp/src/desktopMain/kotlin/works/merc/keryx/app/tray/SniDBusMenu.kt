package works.merc.keryx.app.tray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The interface name the menu's properties live under. */
internal const val DBUSMENU_INTERFACE = "com.canonical.dbusmenu"

private val MENU_KNOWN_IDS = setOf(MENU_ROOT_ID, MENU_TOGGLE_ID, MENU_QUIT_ID)

/**
 * The `/StatusNotifierItem/menu` object exported on the session bus.
 *
 * Like [SniStatusNotifierItem] it holds no `DBusConnection` - [onLayoutUpdated] injects the
 * signal emission - so the revision bookkeeping and event dispatch below are unit-testable
 * without a bus.
 *
 * Thread ownership:
 * - `desired` is written only from the UI thread (via [updateState]) and read from dbus-java
 *   worker threads.
 * - `lastServed` is both written and read only from dbus-java worker threads (`GetLayout` and
 *   `AboutToShow`).
 */
internal class SniDBusMenu(
    private val objectPath: String,
    initialState: TrayMenuState,
    private val onLayoutUpdated: (revision: Int) -> Unit,
) : DBusMenu, Properties {

    private val desired = AtomicReference(initialState)
    private val lastServed = AtomicReference<TrayMenuState?>(null)
    private val revision = AtomicInteger(1)

    private val _toggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val toggleRequests: SharedFlow<Unit> = _toggleRequests

    private val _quitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val quitRequests: SharedFlow<Unit> = _quitRequests

    override fun getObjectPath(): String = objectPath

    /** The revision the host would currently be told about. Exposed for tests. */
    val currentRevision: Int get() = revision.get()

    /**
     * Publishes new menu labels. Bumps the revision and emits `LayoutUpdated` only when the
     * labels actually changed, so a recomposition that leaves them alone costs nothing.
     */
    fun updateState(state: TrayMenuState) {
        val previous = desired.getAndSet(state)
        if (previous != state) {
            onLayoutUpdated(revision.incrementAndGet())
        }
    }

    override fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): MenuLayoutReply {
        val state = desired.get()
        lastServed.set(state)
        return GetLayoutResult(
            UInt32(revision.get().toLong()),
            buildMenuLayout(parentId, recursionDepth, propertyNames, state),
        )
    }

    override fun GetGroupProperties(
        ids: List<Int>,
        propertyNames: List<String>,
    ): List<DBusMenuItemProperties> {
        val state = desired.get()
        return ids.map { DBusMenuItemProperties(it, menuItemProperties(it, state, propertyNames)) }
    }

    override fun GetProperty(id: Int, name: String): Variant<*> =
        menuItemProperties(id, desired.get(), listOf(name))[name] ?: Variant("")

    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        handleEvent(id, eventId)
    }

    override fun EventGroup(events: List<DBusMenuEventEntry>): List<Int> =
        events.filterNot { handleEvent(it.id, it.eventId) }.map { it.id }

    /**
     * `true` when the layout the host last fetched no longer matches the current labels.
     *
     * Answering unconditionally `true` makes some hosts loop AboutToShow -> GetLayout ->
     * AboutToShow; answering unconditionally `false` leaves a stale label behind whenever a
     * `LayoutUpdated` signal is missed. Comparing against what was actually served avoids both.
     */
    override fun AboutToShow(id: Int): Boolean = desired.get() != lastServed.get()

    override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupReply {
        val stale = AboutToShow(MENU_ROOT_ID)
        val known = ids.filter { it in MENU_KNOWN_IDS }
        return AboutToShowGroupResult(
            updatesNeeded = if (stale) known else emptyList(),
            idErrors = ids.filterNot { it in MENU_KNOWN_IDS },
        )
    }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != DBUSMENU_INTERFACE) return emptyMap()
        return mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
        GetAll(interfaceName)[propertyName] as A

    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {
        throw PropertyReadOnly("$interfaceName.$propertyName is read-only")
    }

    /** Returns whether [id] is a menu node we know about. */
    private fun handleEvent(id: Int, eventId: String): Boolean {
        if (id !in MENU_KNOWN_IDS) return false
        if (eventId == "clicked") {
            when (id) {
                MENU_TOGGLE_ID -> _toggleRequests.tryEmit(Unit)
                MENU_QUIT_ID -> _quitRequests.tryEmit(Unit)
            }
        }
        // "hovered" / "opened" / "closed" need no action, but they are still ours to accept.
        return true
    }
}
