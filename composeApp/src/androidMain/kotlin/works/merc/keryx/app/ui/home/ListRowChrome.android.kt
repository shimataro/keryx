package works.merc.keryx.app.ui.home

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

/**
 * [ListRowKind.ListItem] (article) always takes a large rounded rectangle. [ListRowKind.NavItem]
 * (feed/folder/tag) takes the same shape while it's the permanent `PaneLayout.Triple` sidebar pane
 * sitting beside the article list — the two simultaneously visible panes read as one design that
 * way — but a full pill like M3's own `NavigationDrawerItem` while it's actually rendered as
 * feed-list navigation-drawer content ([LocalFeedListInDrawer]), which is never on screen at the
 * same time as the pane it replaces and so has no such need to match it.
 */
@Composable
internal actual fun listRowShape(kind: ListRowKind): Shape = when (kind) {
    ListRowKind.NavItem ->
        if (LocalFeedListInDrawer.current) CircleShape else MaterialTheme.shapes.large
    ListRowKind.ListItem -> MaterialTheme.shapes.large
}

/**
 * Android's selection palette: `secondaryContainer` / `onSecondaryContainer`, M3's own "selected
 * item" pair (what `NavigationDrawerItem` uses), the same whether or not the row's pane holds
 * keyboard focus — M3 itself doesn't change this pair on focus (`ActiveFocusLabelTextColor` equals
 * `ActiveLabelTextColor`). Pane focus is instead expressed as a separate `secondary` outline via
 * [PaneFocusIndication.Ring] — see that type's own KDoc.
 */
@Composable
internal actual fun rowSelectionColors(): RowSelectionColors = RowSelectionColors(
    selectedBackground = MaterialTheme.colorScheme.secondaryContainer,
    selectedContent = MaterialTheme.colorScheme.onSecondaryContainer,
    paneFocus = PaneFocusIndication.Ring(MaterialTheme.colorScheme.secondary),
)
