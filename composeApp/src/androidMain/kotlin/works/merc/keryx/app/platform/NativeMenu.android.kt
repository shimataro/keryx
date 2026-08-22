package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android `actual`: a deliberate no-op for now, not yet the long-press [DropdownMenu][
 * androidx.compose.material3.DropdownMenu] the `expect`'s KDoc anticipates ("on mobile targets
 * this would become long-press instead").
 *
 * Every call site chains this modifier directly after `ui/home/ListRowChrome.kt`'s
 * `listRowClickable` (a plain `Modifier.clickable`) in the same modifier chain — e.g.
 * `Modifier.fillMaxWidth().listRowClickable(...).nativeContextMenu(...)`. Stacking a second
 * gesture detector (`combinedClickable`/`pointerInput { detectTapGestures(onLongPress = ...) }`)
 * on top risks consuming the down/up events before `listRowClickable`'s own tap recognition
 * completes, which would break row selection — Compose's pointer-input passes let an inner
 * modifier (later in the chain, as `nativeContextMenu` is here) observe and consume events before
 * the outer one during the Main pass. Getting long-press vs. tap disambiguation right needs to be
 * verified against a real touch device, which isn't available yet in this phase, so this is left
 * as a no-op rather than risking that regression: primary navigation (subscribe/browse/read) is
 * this phase's acceptance bar, not context-menu actions.
 *
 * Revisit alongside Phase 2's broader touch-input redesign (`ui/home/KeyboardNav.kt`'s
 * desktop-only isolation, hover/right-click retirement) — the fix likely means threading a single
 * `combinedClickable(onClick, onLongClick)` through the row itself instead of two independent
 * modifiers, since [items] is unreachable until a gesture actually opens the menu.
 */
@Composable
actual fun Modifier.nativeContextMenu(
    items: () -> List<NativeMenuEntry>,
    onOpen: () -> Unit,
): Modifier = this
