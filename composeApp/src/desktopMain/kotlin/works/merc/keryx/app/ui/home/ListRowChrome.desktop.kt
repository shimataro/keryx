package works.merc.keryx.app.ui.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Desktop's one row style: an inset, rounded-rectangle highlight — see [ListRowKind]'s own KDoc for
 * why [kind] is ignored here (desktop's macOS-leaning look doesn't distinguish nav-item rows from
 * content-list rows the way Android's Material idioms do).
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
 * Desktop clips every list row the same way — see [ListRowKind]'s own KDoc for why [kind] carries
 * no meaning here.
 */
@Composable
internal actual fun listRowShape(kind: ListRowKind): Shape = MaterialTheme.shapes.small

/**
 * Desktop's selection palette: full-strength `primary` (with `onPrimary` content) in the focused
 * pane, dimmed to [UNFOCUSED_SELECTION_ALPHA] elsewhere via [PaneFocusIndication.Dim] — dim enough
 * that each element's own default text/icon color still reads against it, which is why
 * `HomeCommon.kt`'s non-focused content resolution leaves no override for [PaneFocusIndication.Dim]
 * (`selectedContent` stays `null` there). Desktop represents pane focus entirely through this
 * dimming, not a separate outline (see [PaneFocusIndication.Dim]'s own KDoc).
 */
@Composable
internal actual fun rowSelectionColors(): RowSelectionColors = RowSelectionColors(
    selectedBackground = MaterialTheme.colorScheme.primary,
    selectedContent = MaterialTheme.colorScheme.onPrimary,
    paneFocus = PaneFocusIndication.Dim(UNFOCUSED_SELECTION_ALPHA),
)

/**
 * Alpha of a selected row whose pane does not hold logical focus — desktop-only, since the
 * focused/unfocused axis itself is (see [PaneFocusIndication.Dim]).
 */
private const val UNFOCUSED_SELECTION_ALPHA = 0.4f
