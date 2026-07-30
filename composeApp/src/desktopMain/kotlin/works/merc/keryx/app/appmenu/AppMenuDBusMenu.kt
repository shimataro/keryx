package works.merc.keryx.app.appmenu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.tray.AboutToShowGroupReply
import works.merc.keryx.app.tray.AboutToShowGroupResult
import works.merc.keryx.app.tray.DBUSMENU_INTERFACE
import works.merc.keryx.app.tray.DBusMenu
import works.merc.keryx.app.tray.DBusMenuEventEntry
import works.merc.keryx.app.tray.DBusMenuItemProperties
import works.merc.keryx.app.tray.GetLayoutResult
import works.merc.keryx.app.tray.MenuLayoutReply
import works.merc.keryx.app.tray.ReadOnlyDBusProperties
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import java.util.concurrent.atomic.AtomicReference

/**
 * The `com.canonical.dbusmenu` object describing the application menu tree, exported for the KDE
 * Global Menu. Mirrors `tray/SniDBusMenu`: it holds no `DBusConnection` — [onLayoutUpdated] injects
 * the signal emission — so the revision bookkeeping and event dispatch are unit-testable without a
 * bus, and every exported method returns promptly (host-initiated clicks are published through
 * [clickedIds], collected on the UI thread by `AppMenuBarHost`).
 *
 * Unlike `SniDBusMenu`, [updateState] does **not** dedup on equality: the tree carries action
 * lambdas that are never structurally equal across recompositions, so a no-op guard would never
 * fire. Every call rebuilds the layout and bumps the revision. The relabelling is cheap and the
 * pre-order ids are stable, so this is correct — just not deduplicated.
 *
 * Thread ownership: [current] is written only from the UI thread (via [updateState]) and read from
 * dbus-java worker threads; it bundles the revision with the layout it describes so `GetLayout`
 * cannot serve a revision that disagrees with its layout. [lastServed] is touched only from worker
 * threads (`GetLayout` / `AboutToShow`).
 */
internal class AppMenuDBusMenu(
    private val objectPath: String,
    private val onLayoutUpdated: (revision: Int) -> Unit,
) : DBusMenu, ReadOnlyDBusProperties {

    private class Revision(val revision: Int, val layout: AppMenuLayout)

    private val current = AtomicReference(Revision(revision = 1, layout = buildAppMenuLayout(AppMenuRoot(emptyList()))))
    private val lastServed = AtomicReference<Int?>(null)

    private val _clickedIds = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    /** Ids of items the host reported as `clicked`, dispatched on the UI thread by `AppMenuBarHost`. */
    val clickedIds: SharedFlow<Int> = _clickedIds

    override fun getObjectPath(): String = objectPath

    /** The revision the host would currently be told about. Exposed for tests. */
    val currentRevision: Int get() = current.get().revision

    /** Looks up the actionable node for [id] in the current layout, or `null` if not actionable. */
    fun nodeFor(id: Int): AppMenuNode? = current.get().layout.dispatch[id]

    /**
     * Rebuilds the layout from [root] and bumps the revision, notifying the host via
     * [onLayoutUpdated]. Always rebuilds (no equality dedup — see the class KDoc).
     */
    fun updateState(root: AppMenuRoot) {
        val previous = current.get()
        val next = Revision(previous.revision + 1, buildAppMenuLayout(root))
        current.set(next)
        onLayoutUpdated(next.revision)
    }

    override fun GetLayout(
        parentId: Int,
        recursionDepth: Int,
        propertyNames: List<String>,
    ): MenuLayoutReply {
        val snapshot = current.get()
        lastServed.set(snapshot.revision)
        return GetLayoutResult(
            UInt32(snapshot.revision.toLong()),
            snapshot.layout.buildItem(parentId, recursionDepth, propertyNames),
        )
    }

    override fun GetGroupProperties(
        ids: List<Int>,
        propertyNames: List<String>,
    ): List<DBusMenuItemProperties> {
        val layout = current.get().layout
        return ids.map { DBusMenuItemProperties(it, layout.propertiesOf(it, propertyNames)) }
    }

    override fun GetProperty(id: Int, name: String): Variant<*> =
        current.get().layout.propertiesOf(id, listOf(name))[name] ?: Variant("")

    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        handleEvent(id, eventId)
    }

    override fun EventGroup(events: List<DBusMenuEventEntry>): List<Int> =
        events.filterNot { handleEvent(it.id, it.eventId) }.map { it.id }

    override fun AboutToShow(id: Int): Boolean = current.get().revision != lastServed.get()

    override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupReply {
        val knownIds = current.get().layout.knownIds
        val stale = AboutToShow(APPMENU_ROOT_ID)
        val known = ids.filter { it in knownIds }
        return AboutToShowGroupResult(
            updatesNeeded = if (stale) known else emptyList(),
            idErrors = ids.filterNot { it in knownIds },
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

    /**
     * Accepts an event for a known node. Emits the node id on [clickedIds] only for `clicked`, and
     * never invokes application logic directly (this runs on a dbus-java worker thread).
     *
     * @return `true` when [id] is a known node, `false` otherwise.
     */
    private fun handleEvent(id: Int, eventId: String): Boolean {
        val layout = current.get().layout
        if (id !in layout.knownIds) return false
        if (eventId == "clicked" && id in layout.dispatch) {
            _clickedIds.tryEmit(id)
        }
        // "hovered" / "opened" / "closed" and clicks on submenu/separator nodes need no action,
        // but they are still ours to accept.
        return true
    }
}
