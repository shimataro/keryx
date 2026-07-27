package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.Variant

/** dbusmenu node ids. The root is always 0 per the spec; the two leaves are ours. */
internal const val MENU_ROOT_ID = 0
internal const val MENU_TOGGLE_ID = 1
internal const val MENU_QUIT_ID = 2

/** The D-Bus signature of a dbusmenu node, needed to wrap children in a variant. */
internal const val MENU_LAYOUT_SIGNATURE = "(ia{sv}av)"

/** The labels currently shown in the tray menu. */
internal data class TrayMenuState(val toggleLabel: String, val quitLabel: String)

/**
 * Escapes a menu label for dbusmenu, where a single `_` marks the following character as a
 * mnemonic. The shipped Japanese labels contain none, but a future locale might.
 */
internal fun escapeMenuLabel(label: String): String = label.replace("_", "__")

/**
 * The dbusmenu properties of node [id], filtered to [propertyNames] (an empty list means
 * "all of them"). Unknown ids yield an empty map rather than an error, because hosts probe
 * stale ids after a `LayoutUpdated`.
 */
internal fun menuItemProperties(
    id: Int,
    state: TrayMenuState,
    propertyNames: List<String> = emptyList(),
): Map<String, Variant<*>> {
    val all: Map<String, Variant<*>> = when (id) {
        MENU_ROOT_ID -> mapOf("children-display" to Variant("submenu"))
        MENU_TOGGLE_ID -> leafProperties(state.toggleLabel)
        MENU_QUIT_ID -> leafProperties(state.quitLabel)
        else -> emptyMap()
    }
    if (propertyNames.isEmpty()) return all
    return all.filterKeys { it in propertyNames }
}

private fun leafProperties(label: String): Map<String, Variant<*>> = mapOf(
    "type" to Variant("standard"),
    "label" to Variant(escapeMenuLabel(label)),
    "enabled" to Variant(true),
    "visible" to Variant(true),
)

/**
 * Builds the reply of `GetLayout(parentId, recursionDepth, propertyNames)`.
 *
 * [recursionDepth] follows the dbusmenu spec: `-1` means unlimited, `0` means the requested
 * node without its children, and anything higher limits the depth (our tree is only two
 * levels, so `>= 1` behaves like unlimited).
 */
internal fun buildMenuLayout(
    parentId: Int,
    recursionDepth: Int,
    propertyNames: List<String>,
    state: TrayMenuState,
): DBusMenuLayoutItem {
    val properties = menuItemProperties(parentId, state, propertyNames)
    val childIds = when (parentId) {
        MENU_ROOT_ID -> listOf(MENU_TOGGLE_ID, MENU_QUIT_ID)
        else -> emptyList()
    }
    val includeChildren = recursionDepth != 0
    val children = if (includeChildren) {
        childIds.map { childId ->
            Variant(
                DBusMenuLayoutItem(childId, menuItemProperties(childId, state, propertyNames), emptyList()),
                MENU_LAYOUT_SIGNATURE,
            )
        }
    } else {
        emptyList()
    }
    return DBusMenuLayoutItem(parentId, properties, children)
}
