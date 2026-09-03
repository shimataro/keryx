package works.merc.keryx.app.tray

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.atomic.AtomicReference

/** The interface name the menu's properties live under. */
internal const val DBUSMENU_INTERFACE = "com.canonical.dbusmenu"

// MENU_UPDATE_ID/MENU_SEPARATOR_ID are always "known", even when no update is currently offered
// (TrayMenuState.update == null): a host that cached an earlier layout where they existed can
// still send an Event/AboutToShowGroup naming them, and handleEvent below already no-ops a click
// on a currently-absent-or-disabled update entry — see its own comment.
private val MENU_KNOWN_IDS = setOf(MENU_ROOT_ID, MENU_TOGGLE_ID, MENU_QUIT_ID, MENU_UPDATE_ID, MENU_SEPARATOR_ID)

/**
 * A revision and the labels that revision describes, held together so a worker thread reading
 * both always sees a consistent pair. Two separate atomics would let a concurrent
 * [SniDBusMenu.updateState] stamp the previous layout with the new revision, which a
 * revision-tracking host (libdbusmenu-glib) reads as "already applied" and never refetches.
 */
private data class MenuRevision(val revision: Int, val state: TrayMenuState)

/**
 * The `/StatusNotifierItem/menu` object exported on the session bus.
 *
 * Like [SniStatusNotifierItem] it holds no `DBusConnection` - [onLayoutUpdated] injects the
 * signal emission - so the revision bookkeeping and event dispatch below are unit-testable
 * without a bus.
 *
 * Thread ownership:
 * - `desired` is written only from the UI thread (via [updateState]) and read from dbus-java
 *   worker threads. It carries the revision alongside the labels, so `GetLayout` cannot serve a
 *   revision that disagrees with the layout it returns.
 * - `lastServed` is both written and read only from dbus-java worker threads (`GetLayout` and
 *   `AboutToShow`).
 */
internal class SniDBusMenu(
    private val objectPath: String,
    initialState: TrayMenuState,
    private val onLayoutUpdated: (revision: Int) -> Unit,
) : DBusMenu, ReadOnlyDBusProperties {

    private val desired = AtomicReference(MenuRevision(revision = 1, state = initialState))
    private val lastServed = AtomicReference<TrayMenuState?>(null)

    private val _toggleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val toggleRequests: SharedFlow<Unit> = _toggleRequests

    private val _quitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val quitRequests: SharedFlow<Unit> = _quitRequests

    private val _updateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val updateRequests: SharedFlow<Unit> = _updateRequests

    /**
 * Provides the D-Bus object path for this menu.
 *
 * @return The menu's D-Bus object path.
 */
override fun getObjectPath(): String = objectPath

    /** The revision the host would currently be told about. Exposed for tests. */
    val currentRevision: Int get() = desired.get().revision

    /**
     * Updates the menu state and notifies listeners when it changes.
     *
     * @param state The new menu state.
     */
    fun updateState(state: TrayMenuState) {
        // Single writer (the UI thread), so get-then-set needs no CAS; readers only ever see a
        // whole MenuRevision.
        val previous = desired.get()
        if (previous.state == state) return
        val next = MenuRevision(previous.revision + 1, state)
        desired.set(next)
        onLayoutUpdated(next.revision)
    }

    /**
     * Retrieves the menu layout for the requested parent and recursion depth.
     *
     * @param parentId The ID of the parent menu item.
     * @param recursionDepth The maximum depth of menu items to include.
     * @param propertyNames The properties to include for each menu item.
     * @return The current menu revision and requested menu layout.
     */
    override fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): MenuLayoutReply {
        val snapshot = desired.get()
        lastServed.set(snapshot.state)
        return GetLayoutResult(
            UInt32(snapshot.revision.toLong()),
            buildMenuLayout(parentId, recursionDepth, propertyNames, snapshot.state),
        )
    }

    /**
     * Retrieves the requested properties for each menu item ID.
     *
     * @param ids The menu item IDs whose properties are requested.
     * @param propertyNames The property names to retrieve for each menu item.
     * @return The requested properties grouped by menu item ID.
     */
    override fun GetGroupProperties(
        ids: List<Int>,
        propertyNames: List<String>,
    ): List<DBusMenuItemProperties> {
        val state = desired.get().state
        return ids.map { DBusMenuItemProperties(it, menuItemProperties(it, state, propertyNames)) }
    }

    /**
     * Retrieves a menu item property.
     *
     * @param id The menu item identifier.
     * @param name The property name.
     * @return The property's value, or an empty string variant when the property is unavailable.
     */
    override fun GetProperty(id: Int, name: String): Variant<*> =
        menuItemProperties(id, desired.get().state, listOf(name))[name] ?: Variant("")

    /**
     * Processes a menu event for the specified item.
     *
     * @param id The menu item identifier.
     * @param eventId The event name.
     * @param data The event payload.
     * @param timestamp The event timestamp.
     */
    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        handleEvent(id, eventId)
    }

    /**
     * Handles a group of menu events and returns the IDs of events that were not recognized.
     *
     * @param events The menu events to process.
     * @return The IDs of events that were not handled.
     */
    override fun EventGroup(events: List<DBusMenuEventEntry>): List<Int> =
        events.filterNot { handleEvent(it.id, it.eventId) }.map { it.id }

    /**
 * Determines whether the menu state has changed since the last layout was served.
 *
 * @return `true` if the current state differs from the last served state, `false` otherwise.
 */
    override fun AboutToShow(id: Int): Boolean = desired.get().state != lastServed.get()

    /**
     * Determines which requested menu items require updates and identifies unknown item IDs.
     *
     * @param ids The menu item IDs to evaluate.
     * @return The known IDs requiring updates and the requested IDs that are not recognized.
     */
    override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupReply {
        val stale = AboutToShow(MENU_ROOT_ID)
        val known = ids.filter { it in MENU_KNOWN_IDS }
        return AboutToShowGroupResult(
            updatesNeeded = if (stale) known else emptyList(),
            idErrors = ids.filterNot { it in MENU_KNOWN_IDS },
        )
    }

    /**
     * Retrieves the read-only properties exposed by the DBus menu interface.
     *
     * @param interfaceName The DBus interface whose properties are requested.
     * @return A map of interface properties, or an empty map for an unsupported interface.
     */
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName != DBUSMENU_INTERFACE) return emptyMap()
        return mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    /**
     * Handles an event for a recognized menu node.
     *
     * @param id The menu node receiving the event.
     * @param eventId The event identifier.
     * @return `true` if the menu node is recognized, `false` otherwise.
     */
    private fun handleEvent(id: Int, eventId: String): Boolean {
        if (id !in MENU_KNOWN_IDS) return false
        if (eventId == "clicked") {
            when (id) {
                MENU_TOGGLE_ID -> _toggleRequests.tryEmit(Unit)
                MENU_QUIT_ID -> _quitRequests.tryEmit(Unit)
                // A stale host-side layout can still send a click for an update entry that is now
                // absent or disabled — silently ignored rather than treated as invalid, since the
                // id itself is still a known one (see MENU_KNOWN_IDS's own comment).
                MENU_UPDATE_ID -> if (desired.get().state.update?.enabled == true) _updateRequests.tryEmit(Unit)
            }
        }
        // "hovered" / "opened" / "closed" need no action, but they are still ours to accept.
        return true
    }
}
