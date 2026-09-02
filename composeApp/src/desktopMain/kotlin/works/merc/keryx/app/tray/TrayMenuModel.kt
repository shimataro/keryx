package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.Variant

/** dbusmenu node ids. The root is always 0 per the spec; the leaves are ours. */
internal const val MENU_ROOT_ID = 0
internal const val MENU_TOGGLE_ID = 1
internal const val MENU_QUIT_ID = 2

/** The in-app update entry, inserted ahead of [MENU_TOGGLE_ID] with [MENU_SEPARATOR_ID] between
 * them — see [TrayMenuState.update]'s own KDoc for why these ids exist even on hosts that never
 * see them (an id must be reserved/known before it can ever appear in a layout). */
internal const val MENU_UPDATE_ID = 3
internal const val MENU_SEPARATOR_ID = 4

/** The D-Bus signature of a dbusmenu node, needed to wrap children in a variant. */
internal const val MENU_LAYOUT_SIGNATURE = "(ia{sv}av)"

/** A tray update menu entry's label and whether it can currently be clicked — see
 * `UpdatesTab.kt`'s button-state table for the same state machine rendered in the settings dialog
 * instead of the tray. */
internal data class TrayUpdateEntry(val label: String, val enabled: Boolean)

/**
 * The labels currently shown in the tray menu.
 *
 * @param update The in-app update menu item, or `null` when nothing is offered right now (no
 *   update found, or this install form can't act on one at all) — absent by default so every
 *   existing call site (and every existing test) keeps its original two-item menu shape unchanged.
 */
internal data class TrayMenuState(val toggleLabel: String, val quitLabel: String, val update: TrayUpdateEntry? = null)

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
        MENU_UPDATE_ID -> state.update?.let { leafProperties(it.label, it.enabled) } ?: emptyMap()
        MENU_SEPARATOR_ID -> mapOf("type" to Variant("separator"), "visible" to Variant(true))
        else -> emptyMap()
    }
    if (propertyNames.isEmpty()) return all
    return all.filterKeys { it in propertyNames }
}

/**
 * Creates the standard properties for a leaf menu item.
 *
 * @param label The label displayed for the menu item.
 * @param enabled Whether the item can currently be activated — `false` grays it out (e.g. while a
 *   download is in progress) without removing it from the menu.
 * @return A map containing the item's type, escaped label, enabled state, and visibility.
 */
private fun leafProperties(label: String, enabled: Boolean = true): Map<String, Variant<*>> = mapOf(
    "type" to Variant("standard"),
    "label" to Variant(escapeMenuLabel(label)),
    "enabled" to Variant(enabled),
    "visible" to Variant(true),
)

/**
 * Builds a menu layout item for the requested node.
 *
 * Child items are included when [recursionDepth] is not zero; for this menu, `-1` and positive
 * values include the root's immediate children.
 *
 * @param parentId The ID of the menu node to build.
 * @param recursionDepth The requested child recursion depth.
 * @param propertyNames The property names to include, or an empty list for all properties.
 * @param state The current menu labels.
 * @return The menu layout item for [parentId].
 */
internal fun buildMenuLayout(
    parentId: Int,
    recursionDepth: Int,
    propertyNames: List<String>,
    state: TrayMenuState,
): DBusMenuLayoutItem {
    val properties = menuItemProperties(parentId, state, propertyNames)
    val childIds = when (parentId) {
        MENU_ROOT_ID -> if (state.update != null) {
            listOf(MENU_UPDATE_ID, MENU_SEPARATOR_ID, MENU_TOGGLE_ID, MENU_QUIT_ID)
        } else {
            listOf(MENU_TOGGLE_ID, MENU_QUIT_ID)
        }
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

/**
 * Rounds a download's progress down to the nearest 5% for display in the tray menu label. Showing
 * every single-percent change would fire a `LayoutUpdated` D-Bus signal (or a Win32/AWT repaint)
 * on nearly every 64 KiB chunk of a multi-hundred-megabyte download.
 */
internal fun roundedTrayProgressPercent(bytesDone: Long, bytesTotal: Long): Int {
    if (bytesTotal <= 0) return 0
    val percent = ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
    return (percent / 5) * 5
}
