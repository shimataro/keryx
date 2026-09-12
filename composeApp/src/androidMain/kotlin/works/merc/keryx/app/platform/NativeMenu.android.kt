package works.merc.keryx.app.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.i18n.checkedStateDescription
import works.merc.keryx.app.ui.i18n.uncheckedStateDescription

/**
 * Android `actual`: a real long-press context menu (a Material 3 [DropdownMenu]), replacing the
 * Phase 1 no-op. The gesture is a self-contained `awaitEachGesture` loop rather than
 * `detectTapGestures(onLongPress = ...)`, because that helper consumes the initial *down*
 * unconditionally — which would break `ListRowChrome.kt`'s `listRowClickable` (a plain
 * `Modifier.clickable`, chained right before this one) on every ordinary tap, not just long
 * presses. It never consumes the *down* itself; only once the press survives
 * `viewConfiguration.longPressTimeoutMillis` with no up and no consumption elsewhere (e.g. a
 * `LazyColumn` scroll claiming the gesture) does it treat this as a long press. That detection
 * loop reads [PointerEventPass.Main] (descendant to ancestor) deliberately, since it needs to see
 * whether a descendant — a nested scrollable, or a plain `Modifier.clickable` on an embedded
 * control like a tag row's color dot or an expand chevron — has already claimed the gesture.
 *
 * Once confirmed, though, the *claim* loop that follows switches to [PointerEventPass.Initial]
 * (ancestor to descendant): this node's own consumption then reaches every other node — both
 * `listRowClickable` chained right before it in the modifier chain (the more outer node, whose
 * `Main`-pass tap recognition is naturally *after* this node's Initial-pass consume in the same
 * event) and any `clickable` **nested inside** this row (a descendant, which on `Main` alone would
 * see the same event *before* this node and fire its own `onClick` on release) — before either can
 * observe the event as unconsumed. `PointerInputChange.isConsumed` is shared across every node for
 * a given change (backed by one `consumedDelegate`), so a single `Initial`-pass consume is enough;
 * there is no need to also consume on `Main`. This is what keeps a long press on, say, a tag row's
 * color dot from opening the row's menu *and* also opening the color picker once the finger lifts.
 *
 * [items] is evaluated once the long press is confirmed (matching the `expect`'s contract that it
 * is "only evaluated once the triggering gesture actually completes"), and the resulting menu is
 * anchored via a zero-size `Box` offset to the press position, hosting the [DropdownMenu] — the
 * standard way to position an M3 dropdown at an arbitrary point rather than at a real anchor
 * composable's bounds. [onOpen] (desktop's "right-click selects the row" hook) is deliberately
 * never invoked here — see the `expect` declaration's own KDoc for why.
 */
@Composable
actual fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit,
): Modifier {
    var expanded by remember { mutableStateOf(false) }
    var menuItems by remember { mutableStateOf<List<NativeMenuEntry>>(emptyList()) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val currentItems by rememberUpdatedState(items)

    if (expanded) {
        Box(Modifier.offset { IntOffset(pressOffset.x.toInt(), pressOffset.y.toInt()) }) {
            DropdownMenu(expanded = true, onDismissRequest = { expanded = false }) {
                NativeMenuContent(items = menuItems, onDismiss = { expanded = false })
            }
        }
    }

    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val touchSlop = viewConfiguration.touchSlop
            val stillDownAtTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
                    if (change.changedToUpIgnoreConsumed() || change.isConsumed) return@withTimeoutOrNull
                    if ((change.position - down.position).getDistance() >= touchSlop) return@withTimeoutOrNull
                }
            } == null
            if (stillDownAtTimeout) {
                val resolvedItems = currentItems()
                if (resolvedItems.isNotEmpty()) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // onOpen is deliberately not invoked here — see this function's own KDoc.
                    pressOffset = down.position
                    menuItems = resolvedItems
                    expanded = true
                }
                // Claim the rest of this gesture on the Initial pass — see this function's own
                // KDoc for why that alone is enough to keep both the outer listRowClickable and
                // any nested clickable (e.g. a tag row's color dot) from also firing.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (change.changedToUpIgnoreConsumed()) break
                }
            }
        }
    }
}

/**
 * Renders [items] as [DropdownMenuItem]s, drilling into a [NativeSubMenu] in place (its own items
 * replace the top level, with a leading "back" row) rather than opening a nested popup — there is
 * only ever one level of nesting in practice (see [NativeMenuEntry]'s KDoc), so a single nullable
 * slot is enough to track it. Every leaf click also calls [onDismiss], matching how a real Android
 * menu closes itself on selection.
 */
@Composable
private fun NativeMenuContent(items: List<NativeMenuEntry>, onDismiss: () -> Unit) {
    var openSubMenu by remember { mutableStateOf<NativeSubMenu?>(null) }
    val submenu = openSubMenu

    if (submenu != null) {
        DropdownMenuItem(
            text = { Text(submenu.label) },
            leadingIcon = {
                KeryxIcon(KeryxIcons.ArrowBack, contentDescription = null)
            },
            onClick = { openSubMenu = null },
        )
        HorizontalDivider()
    }

    (submenu?.items ?: items).forEach { entry ->
        when (entry) {
            is NativeMenuItem -> DropdownMenuItem(
                text = { Text(entry.label) },
                enabled = entry.enabled,
                onClick = { entry.onClick(); onDismiss() },
            )
            is NativeCheckMenuItem -> {
                val stateDescription = if (entry.checked) checkedStateDescription() else uncheckedStateDescription()
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    modifier = Modifier.semantics { this.stateDescription = stateDescription },
                    leadingIcon = {
                        // Fixed-size slot so the checkmark's presence never shifts the label (ui-guidelines).
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (entry.checked) KeryxIcon(KeryxIcons.CheckOutlined, contentDescription = null)
                        }
                    },
                    onClick = { entry.onClick(); onDismiss() },
                )
            }
            is NativeSubMenu -> DropdownMenuItem(
                text = { Text(entry.label) },
                trailingIcon = { KeryxIcon(KeryxIcons.ChevronRight, contentDescription = null) },
                onClick = { openSubMenu = entry },
            )
            NativeMenuSeparator -> HorizontalDivider()
        }
    }
}
