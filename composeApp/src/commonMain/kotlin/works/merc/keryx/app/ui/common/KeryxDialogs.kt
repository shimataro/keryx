package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for `androidx.compose.material3.AlertDialog` that renders in a real,
 * separate OS window (`DialogWindow`) instead of a Compose `Popup`. This avoids the Compose
 * Desktop heavyweight/lightweight interop bug where a native AWT panel (the article reader's
 * WebView) always paints on top of Popup-based dialogs in the same window.
 *
 * Deliberately has no `modifier` parameter — the content lives in its own window, not in the
 * caller's composition tree.
 *
 * `title` is a plain string (rather than a composable slot) so it can be handed to the native
 * title bar (`DialogWindow(title = ...)`) as well as, on macOS, redrawn in the merged title-bar
 * area. Any trailing icon action next to the title (e.g. "add folder"/"add tag") is a separate
 * [titleAction] slot. `confirmText`/`onConfirm`/`confirmEnabled`/`dismissText` replace the old
 * `confirmButton`/`dismissButton` composable slots so the buttons can be rendered as real native
 * Swing buttons; when [dismissText] is non-null, clicking it always just calls [onDismissRequest].
 */
@Composable
expect fun KeryxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    title: String? = null,
    titleAction: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    modal: Boolean = true,
)

/** One tab in a [KeryxTabDialog]: a stable [id] used for selection/dispatch, a localized [label]
 * (shown under the icon and, on macOS, mirrored as the window title), and an [icon]. */
data class KeryxDialogTab(val id: String, val label: String, val icon: DrawableResource)

/**
 * A modeless dialog window (see [KeryxAlertDialog] for why a real `DialogWindow` rather than a
 * Compose `Popup`) with a macOS System-Preferences-style tab switcher: an icon+label toolbar tab
 * bar at the top, the selected tab's label mirrored as the window title (next to the traffic
 * lights on macOS), and a fixed-size content area below that top-aligns whichever tab's content is
 * requested. Unlike [KeryxAlertDialog], this window does not block its owner — the
 * main window stays interactive while it is open (matching the real macOS System Settings window).
 *
 * Has no button row: the caller's content applies its changes immediately. The window is closed via
 * the native close box or Escape.
 *
 * @param content receives the currently selected tab's [KeryxDialogTab.id] and renders that tab.
 */
@Composable
expect fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    content: @Composable (String) -> Unit,
)

/**
 * The [KeryxTabDialog] tab bar: a flat, borderless row of icon-over-label tabs in the app's own
 * design language, not a native macOS toolbar/segmented-control mimicry. Two rounds of AWT/Swing
 * interop (Aqua's `"segmented"` and `"toolbarItem"` `JButton.buttonType`s) were tried and dropped —
 * `"segmented"` reads as a cramped joined pill unsuited to this layout, and `"toolbarItem"` doesn't
 * reliably indicate a `JToggleButton`'s selected state under Aqua (a known, still-open JDK bug,
 * JDK-8250953). Native macOS chrome for this control is deferred to a future SwiftUI port instead
 * (see the `ui-guidelines` skill's "Other native-migration candidates") rather than approximated via
 * fragile OS-version-dependent Swing tuning. Plain `Modifier.selectable` gets this dialog's tabs
 * `FlatIndication`'s press feedback and standard Compose keyboard focus/traversal for free.
 *
 * Horizontally scrollable: desktop's fixed dialog width comfortably fits every tab today (see
 * `KERYX_TAB_DIALOG_WIDTH`'s KDoc), so the scroll never engages there, but Android's `KeryxTabDialog`
 * is a full-screen-width `Dialog` — on a phone-width screen, 5 tabs (Cloud Sync and Updates both
 * present) don't fit, and a plain non-scrolling `Row` would run the trailing tab(s) off the physical
 * screen edge with no way to reach them.
 */
@Composable
internal fun KeryxDialogTabBar(
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            val contentColor = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .selectable(selected = selected, onClick = { onSelectTab(tab.id) }, role = Role.Tab)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(tab.icon), contentDescription = null, tint = contentColor, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}
