package works.merc.keryx.app.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import java.awt.Component
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

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
 * `java.awt.MenuItem` has no checked state, so a checked entry is marked in its label instead.
 * Only the AWT backend needs this; Swing has a real checkbox item.
 */
private fun awtLabel(entry: NativeMenuEntry): String =
    if (entry is NativeCheckMenuItem && entry.checked) "✓ ${entry.label}" else entry.label

/**
 * `java.awt.PopupMenu` backend, used on macOS and Windows where AWT maps it onto a genuine
 * platform menu (an `NSMenu` and a Win32 popup menu respectively).
 */
private class AwtPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
) : NativePopupHandle {
    // Not named `components`: see the note on SwingPopupHandle.menuItems. AWT's PopupMenu is not
    // a Container so it wouldn't actually collide here, but keeping the two backends symmetric
    // stops the name from being "tidied" back later.
    private val menuItems: List<java.awt.MenuItem> = items.mapIndexed { index, entry ->
        when (entry) {
            is NativeMenuLeaf ->
                java.awt.MenuItem().apply {
                    addActionListener { leafAt(currentItems(), index, childIndex = null)?.onClick?.invoke() }
                }
            is NativeSubMenu ->
                java.awt.Menu().apply {
                    entry.items.indices.forEach { childIndex ->
                        add(
                            java.awt.MenuItem().apply {
                                addActionListener { leafAt(currentItems(), index, childIndex)?.onClick?.invoke() }
                            },
                        )
                    }
                }
        }
    }

    private val popupMenu = java.awt.PopupMenu().apply { menuItems.forEach { add(it) } }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = menuItems.getOrNull(index) ?: return@forEachIndexed
            component.label = awtLabel(entry)
            if (entry is NativeSubMenu && component is java.awt.Menu) {
                entry.items.forEachIndexed { childIndex, child ->
                    component.getItem(childIndex)?.label = awtLabel(child)
                }
            }
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
 * A structural fingerprint of [items]: the widget kind of every entry, plus each submenu's child
 * count. The native widgets are built once per distinct shape and then only relabelled, so this
 * has to capture everything that decides which components get created — a bare item count would
 * not notice an entry changing kind.
 *
 * Joined strings are safe here, unlike in [menuSignature]: everything encoded comes from the
 * closed vocabulary [leafKind] returns, never from a label, so no user-supplied text can be
 * mistaken for a delimiter.
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
 * A value-comparable description of what [items] currently *renders* — every label and check
 * state, submenu children included.
 *
 * This exists to key the sync effect. The entries themselves cannot be used as a key: they are
 * data classes carrying an `onClick` lambda, and a lambda that captures a row's own data (an
 * `Articles` instance, which Compose does not infer as stable) is a fresh, unequal instance on
 * every recomposition. Keying on the entry list would therefore cancel and relaunch the effect —
 * rewriting the native menu's widgets — for every visible row on every frame while scrolling.
 * Keying on this instead syncs exactly when the menu's visible content actually changed.
 *
 * Nested value objects rather than joined strings, because the labels are user-entered tag and
 * folder names and so can contain whatever would separate them. Encoding a child as
 * `"check:$checked:$label"` and joining with `,` is not injective: a label holding
 * `,check:false:` shifts the boundary between two children, so two genuinely different menus can
 * produce one signature — and the effect that would have relabelled the widgets never reruns.
 */
internal fun menuSignature(items: List<NativeMenuEntry>): List<MenuEntrySignature> =
    items.map { entry ->
        when (entry) {
            is NativeMenuLeaf -> leafSignature(entry)
            is NativeSubMenu -> SubMenuSignature(entry.label, entry.items.map { leafSignature(it) })
        }
    }

private fun leafSignature(entry: NativeMenuLeaf): LeafSignature =
    LeafSignature(entry.label, (entry as? NativeCheckMenuItem)?.checked)

@Composable
actual fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit,
): Modifier {
    val window = LocalNativeWindow.current
    val density = LocalDensity.current
    val currentItems by rememberUpdatedState(items)
    val currentOnOpen by rememberUpdatedState(onOpen)
    var elementPosition by remember { mutableStateOf(Offset.Zero) }

    // Evaluate the caller's lambda once per composition; both descriptors below derive from it.
    val entries = items()

    // The menu's shape is assumed stable per call site across ordinary recompositions - see
    // nativeContextMenu doc. Rebuild the native widgets whenever it actually changes (e.g. a
    // folder is added/removed).
    val handle = remember(menuShape(entries)) {
        val provider = { currentItems() }
        if (isLinux) SwingPopupHandle(entries, provider) else AwtPopupHandle(entries, provider)
    }

    // Keyed on the rendered labels/check states rather than on `entries` itself — see
    // menuSignature. `handle` is included so a rebuilt menu is relabelled even in the (unlikely)
    // case that its shape changed without its signature changing.
    LaunchedEffect(handle, menuSignature(entries)) { handle.sync(currentItems()) }

    DisposableEffect(window, handle) {
        handle.attach(window)
        onDispose { handle.detach(window) }
    }

    return this
        .onGloballyPositioned { coordinates ->
            elementPosition = coordinates.positionInWindow()
        }
        .pointerInput(window, handle) {
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
                        if (currentItems().isNotEmpty()) {
                            val win = window ?: continue
                            val localPosition = event.changes.first().position
                            val x = ((elementPosition.x + localPosition.x) / density.density).toInt()
                            val y = ((elementPosition.y + localPosition.y) / density.density).toInt()
                            // Whether this is synchronous is the backend's call — see
                            // SwingPopupHandle.show.
                            handle.show(win.contentPane, x, y)
                        }
                    }
                }
            }
        }
}
