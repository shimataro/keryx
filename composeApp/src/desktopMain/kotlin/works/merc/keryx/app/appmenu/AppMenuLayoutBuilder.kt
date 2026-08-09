package works.merc.keryx.app.appmenu

import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.tray.DBusMenuLayoutItem
import works.merc.keryx.app.tray.escapeMenuLabel
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import works.merc.keryx.app.ui.menu.AppMenuShortcut

/** The dbusmenu root id is always 0 per the spec. */
internal const val APPMENU_ROOT_ID = 0

/** The D-Bus signature of a dbusmenu node, needed to wrap children in a variant. */
internal const val APPMENU_LAYOUT_SIGNATURE = "(ia{sv}av)"

/**
 * A built `com.canonical.dbusmenu` layout derived from an [AppMenuRoot].
 *
 * Ids are assigned in **pre-order** (root = 0, then encounter order). This is stable across
 * rebuilds because the menu *shape* is fixed at startup (`isMacOs` is a process constant, and the
 * only optional item — "Show Menu Bar" — is added/removed by whether a registrar exists, not at
 * runtime) — **except** for the Feed menu's Tags/Move-to-folder submenus, whose item count follows
 * the live tag/folder lists and can change while the app runs. That variable-length region is still
 * safe: `AppMenuDBusMenu.updateState` bumps the revision on *every* rebuild with no dedup, and
 * `AboutToShow` reports "stale" whenever the revision changed since it was last served to the host —
 * the dbusmenu protocol's own mechanism for forcing a host to refetch the current layout (and
 * therefore the current ids) before it displays a submenu. Stable ids are what make click dispatch
 * by id correct across the constant relabelling every recomposition produces.
 *
 * @property dispatch the actionable ([AppMenuNode.Item] / [AppMenuNode.CheckboxItem]) nodes by id.
 * @property knownIds every id present in the layout (used to accept/reject dbusmenu events).
 */
internal class AppMenuLayout(
    private val nodesById: Map<Int, LayoutNode>,
    val dispatch: Map<Int, AppMenuNode>,
) {
    val knownIds: Set<Int> get() = nodesById.keys

    /** One node's full (unfiltered) properties and child ids. */
    internal class LayoutNode(
        val id: Int,
        val properties: Map<String, Variant<*>>,
        val childIds: List<Int>,
    )

    /**
     * Builds the dbusmenu layout item for [parentId] to [recursionDepth]. A negative depth means
     * "all descendants", `0` means "this node only", and a positive depth includes that many child
     * levels. [propertyNames] filters the properties (empty = all). An unknown id yields an empty
     * item rather than an error, because hosts probe stale ids after a `LayoutUpdated`.
     */
    fun buildItem(parentId: Int, recursionDepth: Int, propertyNames: List<String>): DBusMenuLayoutItem {
        val node = nodesById[parentId]
            ?: return DBusMenuLayoutItem(parentId, emptyMap(), emptyList())
        val includeChildren = recursionDepth != 0 && node.childIds.isNotEmpty()
        val children = if (includeChildren) {
            val childDepth = if (recursionDepth < 0) -1 else recursionDepth - 1
            node.childIds.map { childId ->
                Variant(buildItem(childId, childDepth, propertyNames), APPMENU_LAYOUT_SIGNATURE)
            }
        } else {
            emptyList()
        }
        return DBusMenuLayoutItem(parentId, filterProperties(node.properties, propertyNames), children)
    }

    /** The properties of [id], filtered to [propertyNames] (empty = all). Unknown id → empty map. */
    fun propertiesOf(id: Int, propertyNames: List<String>): Map<String, Variant<*>> =
        filterProperties(nodesById[id]?.properties ?: emptyMap(), propertyNames)

    private fun filterProperties(
        all: Map<String, Variant<*>>,
        propertyNames: List<String>,
    ): Map<String, Variant<*>> = if (propertyNames.isEmpty()) all else all.filterKeys { it in propertyNames }
}

/**
 * Builds an [AppMenuLayout] from [root], assigning ids in pre-order and computing each node's
 * dbusmenu properties. Pure: no bus, no side effects.
 */
internal fun buildAppMenuLayout(root: AppMenuRoot): AppMenuLayout {
    val nodesById = LinkedHashMap<Int, AppMenuLayout.LayoutNode>()
    val dispatch = LinkedHashMap<Int, AppMenuNode>()
    var nextId = APPMENU_ROOT_ID

    fun assign(): Int = nextId++

    fun visit(node: AppMenuNode): Int {
        val id = assign()
        val childIds = when (node) {
            is AppMenuNode.Menu -> node.items.map { visit(it) }
            else -> emptyList()
        }
        nodesById[id] = AppMenuLayout.LayoutNode(id, propertiesFor(node), childIds)
        if (node is AppMenuNode.Item || node is AppMenuNode.CheckboxItem) dispatch[id] = node
        return id
    }

    val rootId = assign() // 0
    val rootChildIds = root.menus.map { visit(it) }
    nodesById[rootId] = AppMenuLayout.LayoutNode(
        rootId,
        mapOf("children-display" to Variant("submenu")),
        rootChildIds,
    )

    return AppMenuLayout(nodesById, dispatch)
}

/** The dbusmenu properties describing a single [AppMenuNode]. */
private fun propertiesFor(node: AppMenuNode): Map<String, Variant<*>> = when (node) {
    is AppMenuNode.Menu -> mapOf(
        "label" to Variant(escapeMenuLabel(node.label)),
        "enabled" to Variant(node.enabled),
        "visible" to Variant(true),
        "children-display" to Variant("submenu"),
    )
    is AppMenuNode.Item -> buildMap {
        put("type", Variant("standard"))
        put("label", Variant(escapeMenuLabel(node.label)))
        put("enabled", Variant(node.enabled))
        put("visible", Variant(true))
        node.shortcut?.let { put("shortcut", it.toDbusmenuShortcut()) }
    }
    is AppMenuNode.CheckboxItem -> buildMap {
        put("type", Variant("standard"))
        put("label", Variant(escapeMenuLabel(node.label)))
        put("enabled", Variant(node.enabled))
        put("visible", Variant(true))
        put("toggle-type", Variant("checkmark"))
        put("toggle-state", Variant(if (node.checked) 1 else 0))
        node.shortcut?.let { put("shortcut", it.toDbusmenuShortcut()) }
    }
    AppMenuNode.Separator -> mapOf(
        "type" to Variant("separator"),
        "visible" to Variant(true),
    )
}

/**
 * The dbusmenu `"shortcut"` property value for this accelerator: an array of one array of
 * modifier/key name strings (spec type `aas`), e.g. `[["Control", "N"]]`. This is what hosts like
 * KDE's Global Menu use to render the accelerator hint next to a label.
 */
private fun AppMenuShortcut.toDbusmenuShortcut(): Variant<*> {
    val combo = buildList {
        if (ctrl) add("Control")
        if (meta) add("Super")
        add(dbusmenuKeyName)
    }
    return Variant(listOf(combo), "aas")
}
