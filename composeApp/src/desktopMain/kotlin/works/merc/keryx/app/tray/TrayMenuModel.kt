package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.Variant

/** dbusmenu node ids. The root is always 0 per the spec; the leaves are ours. */
internal const val MENU_ROOT_ID = DBUSMENU_ROOT_ID
internal const val MENU_TOGGLE_ID = 1
internal const val MENU_QUIT_ID = 2

/** The in-app update entry, always the first child, ahead of [MENU_TOGGLE_ID] with
 * [MENU_SEPARATOR_ID] between them. */
internal const val MENU_UPDATE_ID = 3
internal const val MENU_SEPARATOR_ID = 4

/** The D-Bus signature of a dbusmenu node, needed to wrap children in a variant. */
internal const val MENU_LAYOUT_SIGNATURE = DBUSMENU_LAYOUT_SIGNATURE

/** The single update menu entry's label and whether it can currently be clicked — resolved from
 * `UpdateState` by [updateMenuEntry], which is also what the application menu bar's Help menu uses.
 * See `UpdatesTab.kt`'s button-state table for the same state machine rendered in the settings
 * dialog instead. */
internal data class TrayUpdateEntry(val label: String, val enabled: Boolean)

/**
 * The labels currently shown in the tray menu.
 *
 * @param update The in-app update menu item. Always present: every `UpdateState` maps to a label
 *   (see [updateMenuEntry]), so the menu's shape is fixed and a state with nothing to act on right
 *   now is shown disabled rather than removed.
 */
internal data class TrayMenuState(val toggleLabel: String, val quitLabel: String, val update: TrayUpdateEntry)

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
        MENU_ROOT_ID -> dbusMenuRootProperties()
        MENU_TOGGLE_ID -> dbusMenuLeafProperties(state.toggleLabel)
        MENU_QUIT_ID -> dbusMenuLeafProperties(state.quitLabel)
        MENU_UPDATE_ID -> dbusMenuLeafProperties(state.update.label, state.update.enabled)
        MENU_SEPARATOR_ID -> dbusMenuSeparatorProperties()
        else -> emptyMap()
    }
    return filterDBusMenuProperties(all, propertyNames)
}

/**
 * The dbusmenu items whose properties changed between [previous] and [next], each carrying only
 * the properties that actually changed — the shape `ItemsPropertiesUpdated` expects, and the
 * signal a properties-driven client (e.g. GNOME's AppIndicator extension) relies on to refresh a
 * menu it already has open. Unlike [buildMenuLayout] / `GetLayout`, this never re-derives the
 * unchanged properties: some hosts never re-request `label`/`enabled` via `GetLayout` at all once
 * an item exists, so a full leaf-properties map here would silently overwrite nothing on those
 * hosts but risks masking the real diff on ones that do apply every key blindly.
 *
 * The menu's structure never changes (see [buildMenuLayout]'s fixed `childIds`), so [MENU_ROOT_ID]
 * and [MENU_SEPARATOR_ID] never appear here — only the three leaves can carry a property change.
 */
internal fun changedItemProperties(previous: TrayMenuState, next: TrayMenuState): List<DBusMenuItemProperties> {
    val entries = mutableListOf<DBusMenuItemProperties>()

    if (previous.toggleLabel != next.toggleLabel) {
        entries += DBusMenuItemProperties(MENU_TOGGLE_ID, mapOf("label" to Variant(escapeMenuLabel(next.toggleLabel))))
    }
    if (previous.quitLabel != next.quitLabel) {
        entries += DBusMenuItemProperties(MENU_QUIT_ID, mapOf("label" to Variant(escapeMenuLabel(next.quitLabel))))
    }

    val updateProperties = mutableMapOf<String, Variant<*>>()
    if (previous.update.label != next.update.label) {
        updateProperties["label"] = Variant(escapeMenuLabel(next.update.label))
    }
    if (previous.update.enabled != next.update.enabled) {
        updateProperties["enabled"] = Variant(next.update.enabled)
    }
    if (updateProperties.isNotEmpty()) {
        entries += DBusMenuItemProperties(MENU_UPDATE_ID, updateProperties)
    }

    return entries
}

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
        MENU_ROOT_ID -> listOf(MENU_UPDATE_ID, MENU_SEPARATOR_ID, MENU_TOGGLE_ID, MENU_QUIT_ID)
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
