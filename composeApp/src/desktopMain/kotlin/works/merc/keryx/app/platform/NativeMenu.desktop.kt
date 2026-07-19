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
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu

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

    // Top-level item count, and each NativeSubMenu's child count, are assumed
    // stable per call site across ordinary recompositions - see
    // nativeContextMenu doc. Rebuild the native components whenever either
    // shape actually changes (e.g. a folder is added/removed).
    val itemCount = items().size
    val subMenuChildCounts = items().map { (it as? NativeSubMenu)?.items?.size ?: -1 }
    val menuComponents = remember(itemCount, subMenuChildCounts) {
        items().mapIndexed { index, entry ->
            when (entry) {
                is NativeMenuItem ->
                    MenuItem().apply {
                        addActionListener { (currentItems().getOrNull(index) as? NativeMenuItem)?.onClick?.invoke() }
                    }
                is NativeSubMenu ->
                    Menu().apply {
                        entry.items.indices.forEach { childIndex ->
                            add(
                                MenuItem().apply {
                                    addActionListener {
                                        (currentItems().getOrNull(index) as? NativeSubMenu)
                                            ?.items?.getOrNull(childIndex)?.onClick?.invoke()
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }
    val popupMenu = remember(menuComponents) {
        PopupMenu().apply {
            menuComponents.forEach { add(it) }
        }
    }

    LaunchedEffect(items()) {
        items().forEachIndexed { index, entry ->
            val component = menuComponents.getOrNull(index) ?: return@forEachIndexed
            component.label = entry.label
            if (entry is NativeSubMenu && component is Menu) {
                entry.items.forEachIndexed { childIndex, child ->
                    component.getItem(childIndex)?.label = child.label
                }
            }
        }
    }

    DisposableEffect(window, popupMenu) {
        window?.contentPane?.add(popupMenu)
        onDispose { window?.contentPane?.remove(popupMenu) }
    }

    return this
        .onGloballyPositioned { coordinates ->
            elementPosition = coordinates.positionInWindow()
        }
        .pointerInput(window, popupMenu) {
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
                            popupMenu.show(win.contentPane, x, y)
                        }
                    }
                }
            }
        }
}
