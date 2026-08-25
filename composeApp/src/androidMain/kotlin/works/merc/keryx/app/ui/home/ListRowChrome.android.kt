package works.merc.keryx.app.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Android's two row idioms — see [ListRowKind]'s own KDoc:
 * - [ListRowKind.NavItem] (feed/folder/tag rows) keeps the same inset this app already uses, but
 *   clips to a full pill ([CircleShape]) rather than a lightly-rounded rectangle, matching M3's
 *   `NavigationDrawerItem`. The inset itself ([LIST_ROW_HORIZONTAL_MARGIN] / [LIST_ROW_VERTICAL_MARGIN])
 *   is deliberately unchanged from desktop's — see `ListRowChrome.kt`'s own KDoc on
 *   `extraBottomMargin` for why the vertical one specifically must stay put (the drag insertion
 *   marker's geometry depends on it).
 * - [ListRowKind.ListItem] (article rows) is full-bleed — no horizontal inset, no corner clip —
 *   matching M3's plain `ListItem`. The vertical margin is kept (not the horizontal one) purely for
 *   readable spacing between rows; article rows are never a drag target, so nothing depends on its
 *   exact value the way [ListRowKind.NavItem]'s does.
 */
@Composable
internal actual fun Modifier.listRowSurface(
    background: Color,
    kind: ListRowKind,
    interactionSource: MutableInteractionSource?,
    decoration: Modifier,
    extraBottomMargin: Dp,
): Modifier = when (kind) {
    ListRowKind.NavItem -> this
        .padding(
            start = LIST_ROW_HORIZONTAL_MARGIN,
            end = LIST_ROW_HORIZONTAL_MARGIN,
            top = LIST_ROW_VERTICAL_MARGIN,
            bottom = LIST_ROW_VERTICAL_MARGIN + extraBottomMargin,
        )
        .clip(CircleShape)
        .background(background)
        .then(decoration)
        .let { if (interactionSource != null) it.indication(interactionSource, LocalIndication.current) else it }

    ListRowKind.ListItem -> this
        .padding(top = LIST_ROW_VERTICAL_MARGIN, bottom = LIST_ROW_VERTICAL_MARGIN + extraBottomMargin)
        .background(background)
        .then(decoration)
        .let { if (interactionSource != null) it.indication(interactionSource, LocalIndication.current) else it }
}
