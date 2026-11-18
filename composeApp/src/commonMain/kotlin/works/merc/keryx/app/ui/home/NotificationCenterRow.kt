package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.accessibilityBridgeReadsStateDescription
import works.merc.keryx.app.presentation.home.NotificationBellTone
import works.merc.keryx.app.presentation.home.highestNotificationLevel
import works.merc.keryx.app.presentation.home.notificationBellTone
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_notifications
import works.merc.keryx.app.ui.common.KeryxAnchoredPanel
import works.merc.keryx.app.ui.common.KeryxCountBadge
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.i18n.notificationAlertStateDescription
import works.merc.keryx.app.ui.i18n.notificationRowAlertAccessibleName

/**
 * The bell's color for [tone] (the shared [notificationBellTone]): the error color for an alert, the
 * primary color for notices alone, and the row's own content color when the notification center is
 * empty.
 */
@Composable
internal fun bellTint(tone: NotificationBellTone): Color = when (tone) {
    NotificationBellTone.ALERT -> MaterialTheme.colorScheme.error
    NotificationBellTone.NOTICE -> MaterialTheme.colorScheme.primary
    NotificationBellTone.QUIET -> LocalContentColor.current
}

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

/**
 * The notification center's entry point: a row fixed to the bottom of `FeedListPane`, always shown so
 * it stays in one place (an empty center opens to [NotificationCenterSheet]'s own empty state). The
 * [count] is drawn only when it is above zero, as the same [KeryxCountBadge] pill the narrow layout's
 * drawer button carries, and the bell takes the color of the most severe notification. It replaced the bell in the
 * article list's toolbar, so the toolbar no longer gives a slot to a list that is empty most of the
 * time.
 *
 * Opening it shows [NotificationCenterSheet] in a [KeryxAnchoredPanel] — on desktop a `Popup`
 * opening *above* the row (it sits at the bottom of the window, so opening downward would run off
 * it), on Android a bottom sheet. [onNavigated] runs after a notification's action leads somewhere,
 * once the panel has closed, so a caller hosting this inside the narrow layout's drawer can close
 * the drawer and show the destination.
 */
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
    // Highlighted while it holds keyboard focus (reached with Tab), the way a list row shows the
    // keyboard's position; never otherwise, since this row is not a destination.
    val selected = keyboardFocused && LocalKeyboardEngaged.current
    val items = notifVm.items.collectAsState().value
    val tone = notificationBellTone(items)
    // The tint alone would carry the severity by color only: the button also reports an alert's
    // level as its state, read by assistive technology with the row's name and count.
    val level = highestNotificationLevel(items)
    val alertState = notificationAlertStateDescription(level)
    // Desktop's accessibility bridge drops stateDescription, so there the level also goes into the
    // button's accessible name, spelled out with the row's name and count (which it replaces).
    val alertName = if (accessibilityBridgeReadsStateDescription) null else notificationRowAlertAccessibleName(count, level)
    Box(Modifier.onSizeChanged { rowHeightPx = it.height }) {
        SidebarRow(
            icon = {
                // Tinted by the most severe notification so a warning or an error reads differently
                // from a new-version notice; left to the selection's content color while highlighted.
                val tint = if (selected) LocalContentColor.current else bellTint(tone)
                KeryxIcon(KeryxIcons.Notifications, null, tint = tint)
            },
            label = stringResource(Res.string.home_notifications),
            count = null,
            // The same pill as the narrow layout's drawer button carries, so the two read as one
            // thing — the feed rows' plain unread counts would not. Its number stays in the row's
            // semantics ("Notifications, 2").
            trailing = { KeryxCountBadge(count) },
            selected = selected,
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
                        alertState?.let { stateDescription = it }
                        alertName?.let { contentDescription = it }
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
