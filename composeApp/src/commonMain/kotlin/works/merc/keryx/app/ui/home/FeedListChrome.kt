package works.merc.keryx.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.app_icon
import works.merc.keryx.app.resources.app_name
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_refreshing
import works.merc.keryx.app.resources.home_sync
import works.merc.keryx.app.resources.home_sync_auth_failed
import works.merc.keryx.app.resources.home_syncing
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxPaneTopBar
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * [FeedListPane]'s top toolbar row: an `app_name` title on a platform with no native application
 * menu bar (see [hasNativeAppMenu] below — desktop's own window title bar already names the app,
 * so this stays untitled there), then add feed / refresh all / cloud sync (shown when
 * [cloudConnected], enabled by `HomeViewModel.canSyncNow` — the predicate every "Sync now" route
 * shares).
 * Reads [vm]'s refreshing/syncing state itself (rather than taking it as a parameter) so a
 * refresh/sync toggle only invalidates this row's own restart scope, not the whole pane.
 *
 * The bell lives on `ArticleListPane`'s own header at every layout/depth this pane can be on
 * screen at (Triple's permanent pane, or the narrow drawer, which never displaces the article
 * list the way the old sliding-window Dual/depth-1-Single narrow layouts used to) — see
 * `HomePaneLayout.kt`'s `feedListIsDrawer`/`visiblePanes` — so this row never has to host one.
 *
 * Settings, this pane's other in-pane entry point on such a platform, is *not* rendered here —
 * see [FeedListPane]'s own settings footer row, below its drag-host `Box`.
 *
 * @param hasNativeAppMenu See [FeedListPane]'s own KDoc.
 */
@Composable
internal fun FeedListToolbarRow(
    vm: HomeViewModel,
    cloudConnected: Boolean,
    onAddFeedClick: () -> Unit,
    hasNativeAppMenu: Boolean,
) {
    val activity by vm.activity.collectAsState()
    // The whole refresh-then-sync cycle keeps the refresh spinner up, not just its fetch phase —
    // otherwise it flickers off in the gap before the sync while the button is still disabled
    // (activity.idle is false for the whole cycle). The sync phase hands over to the sync button's
    // own spinner instead; see ActivitySnapshot.refreshIndicatorShown.
    val refreshing = activity.refreshIndicatorShown
    val syncing = activity.syncing
    WindowDragArea(Modifier.fillMaxWidth()) {
        KeryxPaneTopBar(
            modifier = Modifier.padding(top = WindowChrome.titleBarInsetDp.dp, start = 4.dp, end = 4.dp),
            titleContent = if (hasNativeAppMenu) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(Res.drawable.app_icon),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.app_name))
                    }
                }
            },
        ) {
            ToolbarIconGroup {
                val addFeedTooltip = stringResource(Res.string.home_add_feed)
                TooltipIconButton(tooltip = addFeedTooltip, onClick = onAddFeedClick) {
                    KeryxIcon(KeryxIcons.Add, addFeedTooltip)
                }
                val refreshTooltip = stringResource(
                    if (refreshing) Res.string.home_refreshing else Res.string.home_refresh,
                )
                TooltipIconButton(tooltip = refreshTooltip, onClick = { vm.refreshAll() }, enabled = activity.idle) {
                    if (refreshing) {
                        SmallSpinner()
                    } else {
                        KeryxIcon(KeryxIcons.Refresh, refreshTooltip)
                    }
                }
                if (cloudConnected) {
                    // Enabled exactly when every other "Sync now" route is (ManualSync.canSyncNow).
                    // The one disabled state the user must act on — an expired sign-in — says so;
                    // that decision is the shared ManualSync.disabledByAuth, not combined here.
                    val canSyncNow by vm.canSyncNow.collectAsState()
                    val syncDisabledByAuth by vm.syncDisabledByAuth.collectAsState()
                    val syncTooltip = stringResource(
                        when {
                            syncing -> Res.string.home_syncing
                            syncDisabledByAuth -> Res.string.home_sync_auth_failed
                            else -> Res.string.home_sync
                        },
                    )
                    TooltipIconButton(tooltip = syncTooltip, onClick = { vm.sync() }, enabled = canSyncNow) {
                        if (syncing) {
                            SmallSpinner()
                        } else {
                            KeryxIcon(KeryxIcons.Cloud, syncTooltip)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders a clickable sidebar row with an icon, label, optional count badge, and selection styling.
 *
 * @param icon The icon displayed in the row.
 * @param label The row label.
 * @param count The optional count shown when greater than zero.
 * @param selected Whether the row is selected.
 * @param focused Whether the sidebar is focused.
 * @param onClick The action invoked when the row is clicked.
 * @param isTouchPrimary Overridable for tests only — see `feedListReorderDrag`'s own KDoc.
 */
@Composable
internal fun SidebarRow(
    icon: @Composable () -> Unit,
    label: String,
    count: Long?,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .listRowClickable(rowInteraction, selected, onClick)
            .listRowSurface(
                selectionBackground(selected, focused),
                ListRowKind.NavItem,
                rowInteraction,
                decoration = listRowOutline(ListRowKind.NavItem, selected, focused),
            )
            .heightIn(min = listRowMinHeight(isTouchPrimary))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Android follows M3's NavigationDrawerItem, whose label is labelLarge; desktop keeps
                // the ambient text style it has always used.
                style = if (isTouchPrimary) MaterialTheme.typography.labelLarge else LocalTextStyle.current,
            )
        }
        if (count != null && count > 0) CountBadge(count, selected, focused)
    }
}
