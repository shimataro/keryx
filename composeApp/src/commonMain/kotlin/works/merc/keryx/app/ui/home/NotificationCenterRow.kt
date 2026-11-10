package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_notifications
import works.merc.keryx.app.ui.common.KeryxAnchoredPanel
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons

/**
 * The notification center's entry point: a row fixed to the bottom of `FeedListPane`, always shown so
 * it stays in one place (an empty center opens to [NotificationCenterSheet]'s own empty state). The
 * [count] is drawn only when it is above zero (`SidebarRow`'s own rule). It replaced the bell in the
 * article list's toolbar, so the toolbar no longer gives a slot to a list that is empty most of the
 * time.
 *
 * Opening it shows [NotificationCenterSheet] in a [KeryxAnchoredPanel] — on desktop a `Popup`
 * opening *above* the row (it sits at the bottom of the window, so opening downward would run off
 * it), on Android a bottom sheet. [onNavigated] runs after a notification's action leads somewhere,
 * once the panel has closed, so a caller hosting this inside the narrow layout's drawer can close
 * the drawer and show the destination.
 */
/** The gap between [NotificationCenterRow] and the panel that opens above it (desktop only). */
private val PanelGap = 4.dp

/**
 * How many notifications [notifVm] holds, `0` without one. `notifVm` is fixed per call site
 * (`HomeScreen` always passes one; tests may not), so this conditional collect never changes shape
 * between recompositions.
 */
@Composable
internal fun notificationCountOf(notifVm: NotificationCenterViewModel?): Int =
    notifVm?.let { it.items.collectAsState().value.size } ?: 0

@Composable
internal fun NotificationCenterRow(
    notifVm: NotificationCenterViewModel,
    count: Int,
    onNavigated: () -> Unit = {},
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    var showNotifications by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    var keyboardFocused by remember { mutableStateOf(false) }
    val toggle = { showNotifications = !showNotifications }
    Box(Modifier.onSizeChanged { rowHeightPx = it.height }) {
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Notifications, null) },
            label = stringResource(Res.string.home_notifications),
            count = count.toLong(),
            // Highlighted while it holds keyboard focus (reached with Tab), the way a list row shows
            // the keyboard's position; never otherwise, since this row is not a destination.
            selected = keyboardFocused && LocalKeyboardEngaged.current,
            focused = true,
            onClick = toggle,
            // A button that opens a panel, not a selectable destination: announced as a button with
            // its open/closed state, and reachable with Tab, unlike the feed rows (whose pane moves
            // through them with the arrow keys instead).
            clickModifier = { interaction ->
                Modifier
                    .onFocusChanged { keyboardFocused = it.isFocused }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = toggle)
                    .semantics {
                        if (showNotifications) collapse { toggle(); true } else expand { toggle(); true }
                    }
            },
            isTouchPrimary = isTouchPrimary,
        )
        if (showNotifications) {
            KeryxAnchoredPanel(
                onDismissRequest = { showNotifications = false },
                // Bottom-aligned with the row, then lifted by its own height: the panel's bottom
                // edge meets the row's top edge.
                alignment = Alignment.BottomStart,
                anchorOffsetY = with(density) { -rowHeightPx.toDp() } - PanelGap,
            ) {
                // Close the panel once a notification's action leads somewhere, so the destination
                // (feed list selection / settings dialog / explanation dialog) is actually visible.
                NotificationCenterSheet(notifVm, onNavigated = {
                    showNotifications = false
                    onNavigated()
                })
            }
        }
    }
}
