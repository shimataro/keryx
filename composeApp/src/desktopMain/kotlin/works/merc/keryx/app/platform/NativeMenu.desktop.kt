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
import works.merc.keryx.app.core.Log
import java.awt.Component
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The native menu widgets backing one [nativeContextMenu] call site, hiding which toolkit drew
 * them. Two implementations exist because no single one looks native everywhere: see
 * [AwtPopupHandle] and [SwingPopupHandle].
 */
private interface NativePopupHandle {
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
    private val components: List<java.awt.MenuItem> = items.mapIndexed { index, entry ->
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

    private val popupMenu = java.awt.PopupMenu().apply { components.forEach { add(it) } }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = components.getOrNull(index) ?: return@forEachIndexed
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
private class SwingPopupHandle(
    items: List<NativeMenuEntry>,
    currentItems: () -> List<NativeMenuEntry>,
) : NativePopupHandle {
    private val components: List<JMenuItem> = items.mapIndexed { index, entry ->
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

    private val popupMenu = JPopupMenu().apply {
        forceHeavyweight(this)
        components.forEach { add(it) }
    }

    override fun sync(items: List<NativeMenuEntry>) {
        items.forEachIndexed { index, entry ->
            val component = components.getOrNull(index) ?: return@forEachIndexed
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
        // Deferred rather than shown inline. Compose Desktop dispatches input on the EDT, so
        // showing from here would map the menu in the middle of the press being dispatched, and
        // Swing's MenuSelectionManager then reads the rest of that gesture (the release, and the
        // pointer grab Compose still holds) as a click outside the menu — closing it again within
        // the same event burst, so nothing ever appears. Letting the press finish first avoids
        // that. AwtPopupHandle needs none of this: its menu is a native NSMenu/Win32 menu that
        // runs its own modal event loop, and it is shown inline exactly as it always was.
        SwingUtilities.invokeLater {
            popupMenu.show(invoker, x, y)
            debugLogPopup(popupMenu, invoker, x, y)
        }
    }
}

/**
 * Forces [popup] into its own top-level window. A lightweight popup is drawn inside the Compose
 * window, which puts it *behind* the article reader's native WebView — the same heavyweight AWT
 * interop limitation that makes every dialog in this app a separate window (see `KeryxDialogs`).
 */
private fun forceHeavyweight(popup: JPopupMenu) {
    popup.isLightWeightPopupEnabled = !lightweightPopups
}

// ---------------------------------------------------------------------------
// TEMPORARY: switches for diagnosing "the context menu does not appear" on Linux desktops,
// forwarded from Gradle -P properties (see composeApp/build.gradle.kts). Remove all of this,
// and the debugLog calls, once the cause is confirmed.
// ---------------------------------------------------------------------------

/** `-Pkeryx.menu.backend=awt` falls back to the AWT popup that macOS/Windows use. */
private val forceAwtBackend: Boolean = System.getProperty("keryx.menu.backend") == "awt"

/** `-Pkeryx.menu.lightweight=true` undoes [forceHeavyweight], for isolating popup-window issues. */
private val lightweightPopups: Boolean = System.getProperty("keryx.menu.lightweight") == "true"

/** `-Pkeryx.debug.menu=true` logs what the popup did after it was asked to show. */
private val debugMenu: Boolean = System.getProperty("keryx.debug.menu") == "true"

private const val MENU_LOG_TAG = "NativeMenu"

/**
 * Reports whether the popup actually made it onto the screen, right after the show and again
 * once the gesture is fully over — which distinguishes "never mapped" from "mapped then closed
 * again" from "mapped off-screen".
 */
private fun debugLogPopup(popup: JPopupMenu, invoker: Component, x: Int, y: Int) {
    if (!debugMenu) return
    fun describe(phase: String) {
        val location = runCatching { popup.locationOnScreen.let { "${it.x},${it.y}" } }
            .getOrElse { "n/a (${it::class.simpleName})" }
        Log.info(
            MENU_LOG_TAG,
            "$phase: visible=${popup.isVisible} showing=${popup.isShowing} " +
                "size=${popup.size.width}x${popup.size.height} onScreen=$location " +
                "items=${popup.componentCount} lightweight=${popup.isLightWeightPopupEnabled} " +
                "requested=$x,$y invoker=${invoker.width}x${invoker.height}",
        )
    }
    describe("immediately after show")
    Timer(300) { describe("300ms after show") }.apply { isRepeats = false }.start()
}

/**
 * A structural fingerprint of [items]: the widget kind of every entry, plus each submenu's child
 * count. The native widgets are built once per distinct shape and then only relabelled, so this
 * has to capture everything that decides which components get created — a bare item count would
 * not notice an entry changing kind.
 */
private fun menuShape(items: List<NativeMenuEntry>): List<String> = items.map { entry ->
    when (entry) {
        is NativeMenuLeaf -> leafKind(entry)
        is NativeSubMenu -> "sub:" + entry.items.joinToString(",") { leafKind(it) }
    }
}

private fun leafKind(entry: NativeMenuLeaf): String =
    if (entry is NativeCheckMenuItem) "check" else "item"

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

    // The menu's shape is assumed stable per call site across ordinary recompositions - see
    // nativeContextMenu doc. Rebuild the native widgets whenever it actually changes (e.g. a
    // folder is added/removed).
    val handle = remember(menuShape(items())) {
        val snapshot = items()
        val provider = { currentItems() }
        if (isLinux && !forceAwtBackend) SwingPopupHandle(snapshot, provider)
        else AwtPopupHandle(snapshot, provider)
    }

    LaunchedEffect(items()) { handle.sync(items()) }

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
