package works.merc.keryx.app.platform

import java.awt.Component

/**
 * Identifies the menu structure from entry kinds and submenu contents.
 *
 * @param items The menu entries to fingerprint.
 * @return A structural fingerprint for the menu entries.
 */
private fun menuShape(items: List<NativeMenuEntry>): List<String> = items.map { entry ->
    when (entry) {
        is NativeMenuLeaf -> leafKind(entry)
        is NativeSubMenu -> "sub:" + entry.items.joinToString(",") { leafKind(it) }
        is NativeMenuSeparator -> "sep"
    }
}

private fun leafKind(entry: NativeMenuLeaf): String =
    if (entry is NativeCheckMenuItem) "check" else "item"

/** One entry of a [menuSignature]. Internal only so the tests can assert on it. */
internal sealed interface MenuEntrySignature

/**
 * What a leaf renders. [checked] is null for a plain item, so an entry's kind and its check state
 * are one unambiguous field rather than something encoded into text.
 */
internal data class LeafSignature(val label: String, val checked: Boolean?, val enabled: Boolean) : MenuEntrySignature

internal data class SubMenuSignature(
    val label: String,
    val children: List<LeafSignature>,
) : MenuEntrySignature

/** A separator carries no state, so every separator compares equal to every other. */
internal data object SeparatorSignature : MenuEntrySignature

/**
 * Creates a value-comparable representation of the menu's rendered content.
 *
 * @param items The menu entries to represent.
 * @return The labels, checked states, and submenu contents of the menu.
 */
internal fun menuSignature(items: List<NativeMenuEntry>): List<MenuEntrySignature> =
    items.map { entry ->
        when (entry) {
            is NativeMenuLeaf -> leafSignature(entry)
            is NativeSubMenu -> SubMenuSignature(entry.label, entry.items.map { leafSignature(it) })
            is NativeMenuSeparator -> SeparatorSignature
        }
    }

/**
 * Creates a signature for a leaf menu entry from its label and checked state.
 *
 * @param entry The leaf menu entry to describe.
 * @return The entry's rendered label and optional checked state.
 */
private fun leafSignature(entry: NativeMenuLeaf): LeafSignature =
    LeafSignature(entry.label, (entry as? NativeCheckMenuItem)?.checked, entry.isEnabled)

/** Builds a popup handle from a menu's entries and a supplier of its call site's live ones. */
internal typealias NativePopupFactory = (List<NativeMenuEntry>, () -> List<NativeMenuEntry>) -> NativePopupHandle

/**
 * The default [NativePopupFactory]. A lambda rather than `::defaultPopupHandle`: that function's
 * third parameter is defaulted, and spelling the adaptation out keeps the platform constant out of
 * the factory type.
 */
internal val DefaultPopupFactory: NativePopupFactory = { entries, current -> defaultPopupHandle(entries, current) }

/**
 * Owns one call site's native menu and builds it **on the first right-click**, not on composition.
 *
 * Building eagerly is very expensive where it is least affordable. `Container.add(PopupMenu)` calls
 * `Menu.addNotify()`, which creates the native menu peer and one peer per item, and `LazyColumn`
 * re-initializes an item's `remember`/`DisposableEffect` slots every time a row is recycled — so a
 * list scroll used to create and destroy a real `NSMenu`/Win32 menu per row that came into view, on
 * the EDT, for a menu the user usually never opens. Most call sites never open it at all, and three
 * of them pass no entries whatsoever (they only want the `onOpen` side effect).
 *
 * Rebuild/relabel decisions still go through [menuShape] and [menuSignature]; they simply run once
 * per right-click now instead of once per row per composition.
 *
 * A click is resolved against the entries most recently passed to [showFor] — never the call
 * site's live ones — so it performs exactly what the menu displayed; see [leafAt].
 */
internal class LazyNativePopup(
    private val window: NativeWindowHandle?,
    private val factory: NativePopupFactory = DefaultPopupFactory,
) {
    private var handle: NativePopupHandle? = null
    private var builtShape: List<String>? = null
    private var syncedSignature: List<MenuEntrySignature>? = null

    /** The entries of the most recent [showFor]; every click on the widgets resolves against these. */
    private var shownEntries: List<NativeMenuEntry> = emptyList()

    /**
     * Displays the native menu for [entries], rebuilding or synchronizing its widgets when needed.
     *
     * @param entries The menu entries to display.
     * @param invoker The component relative to which the menu is shown.
     * @param x The horizontal display coordinate.
     * @param y The vertical display coordinate.
     */
    fun showFor(entries: List<NativeMenuEntry>, invoker: Component, x: Int, y: Int) {
        // Bound before the widgets are synced or shown, so no click can ever observe the previous
        // show's entries against this show's labels.
        shownEntries = entries
        val shape = menuShape(entries)
        var current = handle
        if (current == null || builtShape != shape) {
            current?.detach(window)
            current = factory(entries) { shownEntries }
            current.attach(window)
            handle = current
            builtShape = shape
            // A fresh menu carries the labels it was built with, so the signature restarts unknown.
            syncedSignature = null
        }
        val signature = menuSignature(entries)
        if (syncedSignature != signature) {
            current.sync(entries)
            syncedSignature = signature
        }
        current.show(invoker, x, y)
    }

    /** Releases the native widgets, if any were ever built. */
    fun dispose() {
        handle?.detach(window)
        handle = null
        builtShape = null
        syncedSignature = null
        shownEntries = emptyList()
    }
}
