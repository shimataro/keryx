package works.merc.keryx.app.platform

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.TextContextMenuArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_copy
import java.awt.Component
import java.awt.MenuShortcut
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
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
 * `java.awt.PopupMenu` backend, used on macOS, where AWT maps it onto a genuine `NSMenu`.
 *
 * Windows used to share this backend — AWT does hand its items to a real Win32 `TrackPopupMenu`
 * there — but the JDK's Windows menu peer is not HiDPI-aware, so above 100% display scaling it
 * both mispositions the menu and paints its items on top of each other. See [SwingPopupHandle]
 * and `known-issues.md`. macOS is unaffected because AppKit is point-based, so the Dp-space
 * coordinates [nativeContextMenu] passes need no device-pixel conversion at all.
 *
 * Internal rather than private so tests can check what actually ended up in the menu (mirrors
 * [SwingPopupHandle]) — load-bearing since [defaultPopupHandle] only picks this backend on macOS,
 * so a Linux or Windows CI runner would otherwise never construct or exercise it at all.
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
            // A MenuItem labelled "-" is the exact idiom java.awt.Menu.addSeparator() itself uses
            // (`add(new MenuItem("-"))`); the native peer renders it as a separator, not a real item.
            is NativeMenuSeparator -> java.awt.MenuItem("-")
        }
    }

    internal val popupMenu = java.awt.PopupMenu().apply { menuItems.forEach { add(it) } }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = menuItems.getOrNull(index) ?: return@forEachIndexed
            // Never relabel a separator: it carries no state, and doing so would overwrite the
            // "-" marker the native peer relies on to render it as a separator.
            if (entry is NativeMenuSeparator) return@forEachIndexed
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
            item.isEnabled = entry.enabled
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
        if (entry is NativeMenuItem) component.isEnabled = entry.enabled
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
 * `javax.swing.JPopupMenu` backend, used everywhere except macOS. Both platforms that pick it do
 * so because AWT's own `PopupMenu` is unusable there, for unrelated reasons:
 *
 * - **Linux**: AWT's `PopupMenu` is a heavyweight XAWT widget that ignores the Swing Look & Feel
 *   entirely, so it keeps its Motif-era appearance no matter how the rest of the app is themed; a
 *   `JPopupMenu` picks up FlatLaf (see `ui/theme/DesktopLookAndFeel.kt`) and matches the Compose
 *   UI around it.
 * - **Windows**: the JDK's Windows menu peer never converts between Java's user space and device
 *   pixels, so every display scale above 100% breaks it twice over. `AwtPopupMenu::Show` feeds the
 *   user-space x/y straight into `MapWindowPoints`/`TrackPopupMenu` without `ScaleUpX/ScaleUpY`,
 *   putting the menu at `windowOrigin + clickOffset / scale`; and `AwtMenuItem::MeasureSelf` fills
 *   `MEASUREITEMSTRUCT.itemHeight` from `FontMetrics.getHeight()` (user space) while the items are
 *   drawn with an HFONT whose `lfHeight` *is* scaled up, so every row ends up `1 / scale` as tall
 *   as its own glyphs and the labels overlap. Upstream: JDK-8259913 (unresolved). Swing paints
 *   through Java2D with the transform applied, so it is correct at any scale — the app's own
 *   `MenuBar` (a `javax.swing.JMenuBar`) already proved that on the same screen.
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
    // JComponent, not JMenuItem: a separator is a JPopupMenu.Separator, which is a JComponent but
    // not a JMenuItem.
    private val menuItems: List<JComponent> = items.mapIndexed { index, entry ->
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
            is NativeMenuSeparator -> JPopupMenu.Separator()
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
            // A separator's JPopupMenu.Separator carries no label/state, so it has nothing to sync.
            if (component is JMenuItem) syncLeaf(component, entry)
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
            item.isEnabled = entry.enabled
            entry.shortcut?.let { shortcut ->
                // Never macOS (see defaultPopupHandle), so the primary modifier is always Ctrl —
                // never Cmd.
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
        if (entry is NativeMenuItem) component.isEnabled = entry.enabled
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
    // Load-bearing on Windows and on the Linux fallback path. Under FlatLaf this is already
    // guaranteed: with Popup.dropShadowPainted on (its default), FlatPopupFactory forces heavy
    // weight for every popup on Linux, overriding this hint either way — but Windows never
    // installs FlatLaf at all (`DesktopLookAndFeel.installLookAndFeel` takes the system L&F
    // branch there), so this line is the only thing keeping the menu out from behind the WebView.
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
        is NativeMenuSeparator -> "sep"
    }
}

private fun leafKind(entry: NativeMenuLeaf): String =
    if (entry is NativeCheckMenuItem) "check" else "item"

/** One entry of a [menuSignature]. Internal only so the tests can assert on it. */
internal sealed interface MenuEntrySignature

/**
 * What a leaf renders. [checked] is null for a plain item, so an entry's kind and its check state
 * are one unambiguous field rather than something encoded into text. [enabled] is always `true`
 * for a [NativeCheckMenuItem] (only [NativeMenuItem] currently supports disabling).
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
    LeafSignature(entry.label, (entry as? NativeCheckMenuItem)?.checked, (entry as? NativeMenuItem)?.enabled ?: true)

/**
 * Builds the backend appropriate to the current platform: [AwtPopupHandle] on macOS, where AWT's
 * `PopupMenu` is a real `NSMenu`, and [SwingPopupHandle] everywhere else, where it is either
 * unthemed (Linux) or outright broken above 100% display scaling (Windows). See both KDocs.
 *
 * @param items The menu entries the widgets are built from.
 * @param currentItems Provides the latest entries when a click has to be resolved to an action.
 * @param macOs Whether this is macOS. A parameter, defaulting to the process constant, purely so
 * a test can pin the mapping on any host — no production call site passes it.
 * @return The popup backend for this platform.
 */
internal fun defaultPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
    macOs: Boolean = isMacOs,
): NativePopupHandle =
    if (macOs) AwtPopupHandle(items, currentItems) else SwingPopupHandle(items, currentItems)

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
    // A lambda rather than `::defaultPopupHandle`: that function's third parameter is defaulted,
    // and spelling the adaptation out keeps the platform constant out of this type.
    private val factory: (List<NativeMenuEntry>, () -> List<NativeMenuEntry>) -> NativePopupHandle =
        { entries, current -> defaultPopupHandle(entries, current) },
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
    hitTest: ((Offset) -> Boolean)?,
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
                        val localPosition = event.changes.first().position
                        if (hitTest?.invoke(localPosition) == false) continue
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

/**
 * The menu entries for a text selection, taken from the selection's own actions.
 *
 * Only [TextContextMenu.TextManager.copy] is ever non-null behind a `SelectionContainer` — its
 * text is read-only, so there is nothing to cut or paste and no select-all — which is why this
 * deliberately maps that one action rather than the full cut/copy/paste/select-all set: an entry
 * whose label could never be shown would still need a translated string in both locales.
 *
 * A copy action with nothing selected is disabled rather than dropped, so the menu keeps the same
 * single row either way. That matches both Compose's own default text menu ([TextContextMenu.Default]
 * shows disabled items) and the rest of this app's native menus, which grey an unavailable action
 * out instead of hiding it.
 *
 * @param textManager The selection whose actions the menu is built from.
 * @param copyLabel The localized label for the copy action.
 * @return The entries to build the native menu widgets from.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun textSelectionMenuEntries(
    textManager: TextContextMenu.TextManager,
    copyLabel: String,
): List<NativeMenuEntry> = listOfNotNull(
    textManager.copy?.let { NativeMenuItem(label = copyLabel, enabled = it.enabled, onClick = it.execute) },
)

/**
 * Where the pointer is, in [owner]'s own coordinate space.
 *
 * Read from the pointer rather than from `ContextMenuState.Status.Open`'s own rect: that rect is in
 * the Compose area's local, density-scaled coordinate space, while a [NativePopupHandle] positions
 * against an AWT component in Java user space. Compose's own `JPopupContextMenuRepresentation`
 * resolves the position exactly this way, and [nativeContextMenu] passes user-space coordinates
 * too, so all the native menus in the app land where the click was.
 *
 * @param owner The component to resolve the pointer position against.
 * @return The pointer position in [owner]'s coordinates, or null when there is no pointer to read.
 */
private fun pointerPositionIn(owner: Component): Point? {
    val onScreen = MouseInfo.getPointerInfo()?.location ?: return null
    return Point(onScreen).also { SwingUtilities.convertPointFromScreen(it, owner) }
}

/**
 * Draws a text selection's context menu with the same native widgets every row context menu uses:
 * [AwtPopupHandle] on macOS, [SwingPopupHandle] elsewhere, picked by the one [defaultPopupHandle].
 *
 * This stands in for Compose Foundation's own `JPopupContextMenuRepresentation` (and for
 * `JPopupTextMenu`, which wraps it) because both are hard-typed to `javax.swing.JPopupMenu` and so
 * can never reach macOS's `java.awt.PopupMenu` — the genuine `NSMenu` a feed- or article-row menu
 * gets there. Everything above the widget stays Compose's: its `TextContextMenuArea` detects the
 * right-click, selects the word under the cursor on macOS, and owns the [ContextMenuState] this
 * reacts to.
 *
 * @param window The window the AWT backend attaches its menu to; null outside a real window.
 * @param owner The component the menu is positioned against — the window's content pane in
 * production, the very component [nativeContextMenu] uses as its invoker.
 * @param entries The entries for the current selection, read afresh on every open.
 * @param factory Builds the backend. Defaulted to the platform choice; a parameter only so a test
 * can observe what is handed to it.
 */
internal class NativeTextSelectionMenuRepresentation(
    window: NativeWindowHandle?,
    private val owner: Component,
    private val entries: () -> List<NativeMenuEntry>,
    factory: (List<NativeMenuEntry>, () -> List<NativeMenuEntry>) -> NativePopupHandle =
        { items, current -> defaultPopupHandle(items, current) },
) : ContextMenuRepresentation {

    // Built on the first open and kept for as long as the selection's area is composed, rather than
    // rebuilt per open the way Compose's own representation does. The AWT backend's menu has to
    // stay in the window's hierarchy while it is on screen — detaching it destroys its native peer
    // — and nothing here can tell when that menu has been dismissed (see Representation). One menu
    // per call site, relabelled from the current selection before each show, sidesteps that
    // entirely; [dispose] is what finally releases it.
    private val popup = LazyNativePopup(window = window, currentItems = entries, factory = factory)

    /**
     * Shows the native menu whenever [state] says the selection's menu has been opened.
     *
     * @param state The menu state Compose's `TextContextMenuArea` opens.
     * @param items Ignored. It only ever carries items a surrounding `ContextMenuDataProvider`
     * contributed, and this app adds none; the entries come from the selection's own `TextManager`
     * (see [textSelectionMenuEntries]) instead. `JPopupTextMenu` hands them over the same way.
     */
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        if (state.status !is ContextMenuState.Status.Open) return
        DisposableEffect(Unit) {
            pointerPositionIn(owner)?.let { showAt(it.x, it.y) }
            // Closed the moment the menu has been handed to the toolkit, not when it is actually
            // dismissed. Neither backend's menu is drawn by Compose — each is a real, separate
            // window that dismisses itself — so nothing reads this state except the decision to
            // *open* a menu, and leaving it Open would swallow the next right-click. Compose's own
            // JPopupContextMenuRepresentation can wait for PopupMenuListener's
            // popupMenuWillBecomeInvisible because it only ever builds a Swing menu;
            // java.awt.PopupMenu has no equivalent hook at all, and closing at a different moment
            // per platform is exactly the divergence this class exists to remove.
            state.status = ContextMenuState.Status.Closed
            // Deliberately empty: this effect is disposed by the very state write above, so hiding
            // or detaching anything here would tear down the menu that was just shown.
            onDispose {}
        }
    }

    /**
     * Shows the menu for the current selection.
     *
     * Split out of [Representation] so the widget side can be exercised without a real window:
     * deciding *when* to show is Compose's, and everything below this is [LazyNativePopup]'s.
     *
     * @param x The horizontal display coordinate, in [owner]'s space.
     * @param y The vertical display coordinate, in [owner]'s space.
     */
    internal fun showAt(x: Int, y: Int) {
        val current = entries()
        // Nothing to show rather than an empty box — the same guard [nativeContextMenu] applies,
        // and what Compose's own DefaultContextMenuRepresentation does with no components. Not
        // reachable today: a `SelectionContainer` always offers a copy action.
        if (current.isEmpty()) return
        popup.showFor(current, owner, x, y)
    }

    /** Releases the native widgets. Called when the selection's area leaves the composition. */
    fun dispose() {
        popup.dispose()
    }
}

/**
 * The [TextContextMenu] a selection is given on desktop: the same native menu [nativeContextMenu]
 * builds for a feed or article row, on every platform — see [NativeTextSelectionMenuRepresentation].
 *
 * @param window The window the menu belongs to.
 * @param copyLabel The localized label for the copy action.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class NativeTextContextMenu(
    private val window: NativeWindowHandle,
    private val copyLabel: String,
) : TextContextMenu {

    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        val representation = remember(window, textManager, copyLabel) {
            NativeTextSelectionMenuRepresentation(
                window = window,
                owner = window.contentPane,
                entries = { textSelectionMenuEntries(textManager, copyLabel) },
            )
        }
        DisposableEffect(representation) { onDispose { representation.dispose() } }
        CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
            TextContextMenuArea(
                textManager = textManager,
                // Empty by design — see NativeTextSelectionMenuRepresentation.Representation.
                items = { emptyList() },
                state = state,
                content = content,
            )
        }
    }
}

/**
 * Desktop `actual`: gives a text selection the app's own native context menu instead of Compose
 * Foundation's Compose-drawn popup, down to the same widget class as a row menu's on every
 * platform — `java.awt.PopupMenu` (a real `NSMenu`) on macOS, `javax.swing.JPopupMenu` on Windows
 * and Linux.
 *
 * Compose keeps everything but the widget: the items come from the selection's own actions, and
 * the right-click detection (including macOS's select-the-word-under-the-cursor behavior) stays
 * with its `TextContextMenuArea`, so nothing here competes with the selection gestures around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun NativeTextSelectionContextMenu(content: @Composable () -> Unit) {
    val window = LocalNativeWindow.current
    val copyLabel = stringResource(Res.string.common_copy)
    val textContextMenu = remember(window, copyLabel) {
        // With no window there is nothing to attach or position a native menu against (a
        // `ComposePanel` test host, or composition before the window exists), so fall back to
        // Compose's own menu rather than leaving the selection with no menu at all.
        if (window == null) TextContextMenu.Default else NativeTextContextMenu(window, copyLabel)
    }
    CompositionLocalProvider(LocalTextContextMenu provides textContextMenu, content = content)
}
