package works.merc.keryx.app.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import java.awt.Component
import java.awt.MenuShortcut
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * The AWT virtual-key code for [NativeMenuShortcut.key]. Covers only the keys actually used by a
 * [NativeMenuItem] shortcut today (R/U/S/O/C for the Ctrl+Shift app-menu-mirrored actions, F2/
 * Enter/Delete for the bare rename/delete family) — deliberately not a general `Key` → `VK_*`
 * mapping. `appmenu/MenuBarVisibility.kt` has its own, separate `awtKeyCode()` for `AppMenuShortcut`;
 * that lives in the KDE-Global-Menu-specific `appmenu/` package, so pulling `platform/` code from
 * it would be a backwards layering dependency — this is kept local instead.
 */
private fun NativeMenuShortcut.awtKeyCode(): Int = when (key) {
    Key.R -> KeyEvent.VK_R
    Key.U -> KeyEvent.VK_U
    Key.S -> KeyEvent.VK_S
    Key.O -> KeyEvent.VK_O
    Key.C -> KeyEvent.VK_C
    Key.F2 -> KeyEvent.VK_F2
    Key.Enter -> KeyEvent.VK_ENTER
    Key.Delete -> KeyEvent.VK_DELETE
    else -> error("No AWT key-code mapping for native menu shortcut key $key")
}

/**
 * The native menu widgets backing one [nativeContextMenu] call site, hiding which toolkit drew
 * them. Two implementations exist because no single one looks native everywhere: see
 * [AwtPopupHandle] and [SwingPopupHandle].
 */
internal interface NativePopupHandle {
    /** Pushes the current [items] labels and checked states onto the already-built widgets. */
    fun sync(items: List<NativeMenuEntry>)

    /** Adds the menu to [window], for toolkits that require it to be part of the hierarchy. */
    fun attach(window: NativeWindowHandle?)

    fun detach(window: NativeWindowHandle?)

    /** Shows the menu. May display on a later EDT turn rather than before returning. */
    fun show(invoker: Component, x: Int, y: Int)
}

/**
 * Resolves the leaf that a click on the widget at [index] (and [childIndex], for a submenu child)
 * should invoke, against the *latest* items rather than the ones the widgets were built from.
 */
private fun leafAt(items: List<NativeMenuEntry>, index: Int, childIndex: Int?): NativeMenuLeaf? {
    val entry = items.getOrNull(index) ?: return null
    return if (childIndex == null) entry as? NativeMenuLeaf
    else (entry as? NativeSubMenu)?.items?.getOrNull(childIndex)
}

/**
 * `java.awt.PopupMenu` backend, used on macOS and Windows where AWT maps it onto a genuine
 * platform menu (an `NSMenu` and a Win32 popup menu respectively).
 *
 * Internal rather than private so tests can check what actually ended up in the menu (mirrors
 * [SwingPopupHandle]) — load-bearing since [defaultPopupHandle] only picks this backend on
 * macOS/Windows, so a Linux CI runner would otherwise never construct or exercise it at all.
 */
internal class AwtPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
) : NativePopupHandle {
    // Not named `components`: see the note on SwingPopupHandle.menuItems. AWT's PopupMenu is not
    // a Container so it wouldn't actually collide here, but keeping the two backends symmetric
    // stops the name from being "tidied" back later.
    private val menuItems: List<java.awt.MenuItem> = items.mapIndexed { index, entry ->
        when (entry) {
            is NativeMenuLeaf ->
                awtLeaf(entry) { leafAt(currentItems(), index, childIndex = null)?.onClick?.invoke() }
            is NativeSubMenu ->
                java.awt.Menu().apply {
                    entry.items.forEachIndexed { childIndex, child ->
                        add(awtLeaf(child) { leafAt(currentItems(), index, childIndex)?.onClick?.invoke() })
                    }
                }
        }
    }

    internal val popupMenu = java.awt.PopupMenu().apply { menuItems.forEach { add(it) } }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = menuItems.getOrNull(index) ?: return@forEachIndexed
            syncLeaf(component, entry)
            if (entry is NativeSubMenu && component is java.awt.Menu) {
                entry.items.forEachIndexed { childIndex, child ->
                    component.getItem(childIndex)?.let { syncLeaf(it, child) }
                }
            }
        }
    }

    /**
     * `java.awt.CheckboxMenuItem` (a `MenuItem` subclass) for a [NativeCheckMenuItem], so its
     * checked state is drawn with the platform's own checkmark and gutter instead of being faked
     * into the label text — that fake left unchecked siblings flush left while checked ones sat
     * two characters in, which is the misalignment this fixes. A plain [java.awt.MenuItem]
     * otherwise.
     */
    private fun awtLeaf(entry: NativeMenuLeaf, onClick: () -> Unit): java.awt.MenuItem {
        val item = if (entry is NativeCheckMenuItem) java.awt.CheckboxMenuItem() else java.awt.MenuItem()
        // A native checkbox menu item's peer reports selection as an ItemEvent, not an
        // ActionEvent (unlike Swing's JCheckBoxMenuItem, which fires ActionEvent for both — see
        // SwingPopupHandle.swingLeaf). A plain MenuItem only ever fires ActionEvent.
        if (item is java.awt.CheckboxMenuItem) {
            item.addItemListener { onClick() }
        } else {
            item.addActionListener { onClick() }
        }
        // A bare (ctrl = false) shortcut — the rename/delete family — has no MenuShortcut
        // representation at all: the class always bakes in the platform's primary modifier, so it
        // can only show a modifier'd combo correctly. Those items render with no native hint here;
        // see NativeMenuShortcut's doc comment.
        if (entry is NativeMenuItem) {
            entry.shortcut?.takeIf { it.ctrl }?.let { item.shortcut = MenuShortcut(it.awtKeyCode(), it.shift) }
        }
        return item
    }

    private fun syncLeaf(component: java.awt.MenuItem, entry: NativeMenuEntry) {
        component.label = entry.label
        // Set through the model, not re-derived from the label, so a click (which toggles the
        // native item itself) is corrected back to the app's own state on the next sync — same
        // reasoning as SwingPopupHandle.syncLeaf.
        if (component is java.awt.CheckboxMenuItem && entry is NativeCheckMenuItem) {
            component.state = entry.checked
        }
    }

    // An AWT PopupMenu is only showable once it belongs to a component's menu hierarchy.
    override fun attach(window: NativeWindowHandle?) {
        window?.contentPane?.add(popupMenu)
    }

    override fun detach(window: NativeWindowHandle?) {
        window?.contentPane?.remove(popupMenu)
    }

    override fun show(invoker: Component, x: Int, y: Int) {
        popupMenu.show(invoker, x, y)
    }
}

/**
 * `javax.swing.JPopupMenu` backend, used on Linux. AWT's `PopupMenu` there is a heavyweight XAWT
 * widget that ignores the Swing Look & Feel entirely, so it keeps its Motif-era appearance no
 * matter how the rest of the app is themed; a `JPopupMenu` picks up FlatLaf (see
 * `ui/theme/DesktopLookAndFeel.kt`) and matches the Compose UI around it.
 */
internal class SwingPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
) : NativePopupHandle {
    // Deliberately NOT named `components`. Inside the `apply` below the implicit receiver is the
    // JPopupMenu, which extends Container and therefore exposes a synthetic `components` property
    // (Container.getComponents()). That receiver wins name resolution over a field of this class,
    // so a field called `components` would silently resolve to the popup's own — empty — child
    // array, adding nothing and leaving a menu that shows as a 0x0 nothing. AWT's PopupMenu is
    // not a Container, which is why only the Swing backend was ever affected.
    private val menuItems: List<JMenuItem> = items.mapIndexed { index, entry ->
        when (entry) {
            is NativeMenuLeaf ->
                swingLeaf(entry) { leafAt(currentItems(), index, childIndex = null)?.onClick?.invoke() }
            is NativeSubMenu ->
                JMenu().apply {
                    // A submenu opens through its own popup, which needs the same treatment as
                    // the root one below. Called as the getter to keep it unambiguous that this
                    // is the JMenu's popup, not this class's `popupMenu` field.
                    forceHeavyweight(getPopupMenu())
                    entry.items.forEachIndexed { childIndex, child ->
                        add(swingLeaf(child) { leafAt(currentItems(), index, childIndex)?.onClick?.invoke() })
                    }
                }
        }
    }

    /** Internal rather than private so tests can check what actually ended up in the menu. */
    internal val popupMenu = JPopupMenu().apply {
        forceHeavyweight(this)
        menuItems.forEach { add(it) }
    }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = menuItems.getOrNull(index) ?: return@forEachIndexed
            syncLeaf(component, entry)
            if (entry is NativeSubMenu && component is JMenu) {
                entry.items.forEachIndexed { childIndex, child ->
                    component.getItem(childIndex)?.let { syncLeaf(it, child) }
                }
            }
        }
    }

    private fun swingLeaf(entry: NativeMenuLeaf, onClick: () -> Unit): JMenuItem {
        val item = if (entry is NativeCheckMenuItem) JCheckBoxMenuItem() else JMenuItem()
        item.addActionListener { onClick() }
        // Safe even for a bare (ctrl = false) key: this JPopupMenu is never attached to a
        // JMenuBar/JRootPane (see the note on `attach` below), so Swing's automatic
        // WHEN_IN_FOCUSED_WINDOW global keybinding registration — which only walks from a
        // JMenuBar's structure — never reaches it. Setting `accelerator` here is purely cosmetic:
        // it renders in Swing's own native accelerator column with no live global keybinding.
        if (entry is NativeMenuItem) {
            entry.shortcut?.let { shortcut ->
                // Linux only, so the primary modifier is always Ctrl — never Cmd.
                var modifiers = 0
                if (shortcut.ctrl) modifiers = modifiers or InputEvent.CTRL_DOWN_MASK
                if (shortcut.shift) modifiers = modifiers or InputEvent.SHIFT_DOWN_MASK
                item.accelerator = KeyStroke.getKeyStroke(shortcut.awtKeyCode(), modifiers)
            }
        }
        return item
    }

    private fun syncLeaf(component: JMenuItem, entry: NativeMenuEntry) {
        component.text = entry.label
        // Set through the model so re-labelling a checkbox item can't drop its tick, and so a
        // click (which toggles the item itself) is corrected back to the app's own state.
        if (component is JCheckBoxMenuItem && entry is NativeCheckMenuItem) {
            component.isSelected = entry.checked
        }
    }

    // JPopupMenu.show takes the invoker directly, so unlike AWT there is nothing to add to the
    // window's hierarchy up front.
    override fun attach(window: NativeWindowHandle?) = Unit

    override fun detach(window: NativeWindowHandle?) = Unit

    override fun show(invoker: Component, x: Int, y: Int) {
        // Deferred rather than shown inline: Swing requires show() on the EDT, and this keeps the
        // menu from being mapped in the middle of Compose Desktop dispatching the press that
        // asked for it. AwtPopupHandle needs neither — its menu is a native NSMenu/Win32 menu
        // with its own modal event loop — so it stays inline exactly as it always was.
        SwingUtilities.invokeLater {
            // Re-apply the current Look & Feel. This popup belongs to no window's component tree,
            // so FlatLaf.updateUI() — which walks Window.getWindows() — structurally cannot reach
            // it, and it would otherwise keep whatever L&F was in force when it was built. That
            // matters both for an in-app theme change and for the case where the L&F is installed
            // later than the popup. Submenus come along: updateComponentTreeUI recurses through
            // JMenu.getMenuComponents(), and JMenu.updateUI() re-applies its own popup's UI.
            SwingUtilities.updateComponentTreeUI(popupMenu)
            popupMenu.show(invoker, x, y)
        }
    }
}

/**
 * Forces [popup] into its own top-level window. A lightweight popup is drawn inside the Compose
 * window, which puts it *behind* the article reader's native WebView — the same heavyweight AWT
 * interop limitation that makes every dialog in this app a separate window (see `KeryxDialogs`).
 */
private fun forceHeavyweight(popup: JPopupMenu) {
    // Only load-bearing on the fallback path. Under FlatLaf this is already guaranteed: with
    // Popup.dropShadowPainted on (its default), FlatPopupFactory forces heavy weight for every
    // popup on Linux, overriding this hint either way.
    popup.isLightWeightPopupEnabled = false
}

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
internal data class LeafSignature(val label: String, val checked: Boolean?) : MenuEntrySignature

internal data class SubMenuSignature(
    val label: String,
    val children: List<LeafSignature>,
) : MenuEntrySignature

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
        }
    }

/**
     * Creates a signature for a leaf menu entry from its label and checked state.
     *
     * @param entry The leaf menu entry to describe.
     * @return The entry's rendered label and optional checked state.
     */
    private fun leafSignature(entry: NativeMenuLeaf): LeafSignature =
    LeafSignature(entry.label, (entry as? NativeCheckMenuItem)?.checked)

/** Builds the backend appropriate to the current platform. */
internal fun defaultPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
): NativePopupHandle =
    if (isLinux) SwingPopupHandle(items, currentItems) else AwtPopupHandle(items, currentItems)

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
 */
internal class LazyNativePopup(
    private val window: NativeWindowHandle?,
    private val currentItems: () -> List<NativeMenuEntry>,
    private val factory: (List<NativeMenuEntry>, () -> List<NativeMenuEntry>) -> NativePopupHandle =
        ::defaultPopupHandle,
) {
    private var handle: NativePopupHandle? = null
    private var builtShape: List<String>? = null
    private var syncedSignature: List<MenuEntrySignature>? = null

    /**
     * Displays the native menu for [entries], rebuilding or synchronizing its widgets when needed.
     *
     * @param entries The menu entries to display.
     * @param invoker The component relative to which the menu is shown.
     * @param x The horizontal display coordinate.
     * @param y The vertical display coordinate.
     */
    fun showFor(entries: List<NativeMenuEntry>, invoker: Component, x: Int, y: Int) {
        val shape = menuShape(entries)
        var current = handle
        if (current == null || builtShape != shape) {
            current?.detach(window)
            current = factory(entries, currentItems)
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
    }
}

/**
 * Adds a native context menu that opens when the secondary mouse button is pressed.
 *
 * @param items Provides the current menu entries.
 * @param onOpen Called when the context menu is requested.
 * @return A modifier that handles native context menu interaction.
 */
@Composable
actual fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit,
): Modifier {
    val window = LocalNativeWindow.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentOnOpen by rememberUpdatedState(onOpen)
    // A plain holder, not a snapshot state: this is written from onGloballyPositioned on every
    // layout of every attached element (so, per row per scroll frame) and only ever read from the
    // suspending pointer handler below, never from composition. As snapshot state it would record
    // a write per layout for no reader.
    val elementPosition = remember { FloatArray(2) }

    // Nothing native is built until the first right-click — see LazyNativePopup.
    val popup = remember(window) {
        LazyNativePopup(window = window, currentItems = { currentItems() })
    }
    DisposableEffect(popup) { onDispose { popup.dispose() } }

    return this
        .onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            elementPosition[0] = position.x
            elementPosition[1] = position.y
        }
        .pointerInput(window, popup) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent()
                    if (
                        event.type == PointerEventType.Press &&
                        event.buttons.isSecondaryPressed &&
                        event.changes.none { it.isConsumed }
                    ) {
                        event.changes.forEach { it.consume() }
                        currentOnOpen()
                        val entries = currentItems()
                        if (entries.isNotEmpty()) {
                            val win = window ?: continue
                            val localPosition = event.changes.first().position
                            val x = ((elementPosition[0] + localPosition.x) / density.density).toInt()
                            val y = ((elementPosition[1] + localPosition.y) / density.density).toInt()
                            // Whether this is synchronous is the backend's call — see
                            // SwingPopupHandle.show.
                            popup.showFor(entries, win.contentPane, x, y)
                        }
                    }
                }
            }
        }
}
