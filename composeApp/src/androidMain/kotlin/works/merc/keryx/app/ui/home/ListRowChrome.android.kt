package works.merc.keryx.app.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Android's row surface — the same inset for both [ListRowKind]s ([listRowHorizontalMargin] /
 * [LIST_ROW_VERTICAL_MARGIN], the latter deliberately unchanged from desktop's; see
 * `ListRowChrome.kt`'s KDoc on `extraBottomMargin` for why the vertical one specifically must stay
 * put, the drag insertion marker's geometry depending on it), differing only in the corner
 * treatment [listRowShape] gives each.
 *
 * The feed list and the article list sit side by side at `PaneLayout.Triple`, so a row that bled to
 * the pane edge in one and floated inside an inset in the other read as two unrelated designs
 * rather than as two levels of one hierarchy — hence one shared inset for both.
 */
@Composable
internal actual fun Modifier.listRowSurface(
    background: Color,
    kind: ListRowKind,
    interactionSource: MutableInteractionSource?,
    decoration: Modifier,
    extraBottomMargin: Dp,
): Modifier = this
    .padding(
        start = listRowHorizontalMargin(),
        end = listRowHorizontalMargin(),
        top = LIST_ROW_VERTICAL_MARGIN,
        bottom = LIST_ROW_VERTICAL_MARGIN + extraBottomMargin,
    )
    .clip(listRowShape(kind))
    .background(background)
    .then(decoration)
    .let { if (interactionSource != null) it.indication(interactionSource, LocalIndication.current) else it }

/**
 * Each [ListRowKind] takes the corner treatment of the M3 component it is modeled on: a
 * [ListRowKind.NavItem] (feed/folder/tag) is a full pill like `NavigationDrawerItem`, a
 * [ListRowKind.ListItem] (article) a large rounded rectangle — a card-like sibling of the pill
 * rather than the same shape, so the two panes stay distinguishable while sharing one visual
 * language.
 */
@Composable
internal actual fun listRowShape(kind: ListRowKind): Shape = when (kind) {
    ListRowKind.NavItem -> CircleShape
    ListRowKind.ListItem -> MaterialTheme.shapes.large
}

/**
 * Android's selection palette: `secondaryContainer` / `onSecondaryContainer`, M3's own "selected
 * item" pair (what `NavigationDrawerItem` uses), the same whether or not the row's pane holds
 * keyboard focus — M3 itself doesn't change this pair on focus (`ActiveFocusLabelTextColor` equals
 * `ActiveLabelTextColor`). Pane focus is instead expressed as a separate `secondary` outline via
 * [focusRing] — see [RowSelectionColors]'s own KDoc.
 */
@Composable
internal actual fun rowSelectionColors(): RowSelectionColors {
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
    return RowSelectionColors(
        focusedBackground = secondaryContainer,
        focusedContent = onSecondaryContainer,
        unfocusedBackground = secondaryContainer,
        unfocusedContent = onSecondaryContainer,
        echoBackground = secondaryContainer.copy(alpha = SECONDARY_SELECTION_ALPHA),
        focusRing = MaterialTheme.colorScheme.secondary,
    )
}
