package works.merc.keryx.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * How strongly a feed-list row paints its selection. A feed renders once under its folder group and
 * again under every expanded tag it carries, so "selected" is not a single row: exactly one rendered
 * instance is the one actually clicked/keyboard-navigated to ([PRIMARY]), and every other instance of
 * the same selected feed is a [SECONDARY] echo of it. [NONE] is an unselected row.
 */
enum class RowSelectionTone { NONE, SECONDARY, PRIMARY }

/**
 * Alpha of a [RowSelectionTone.SECONDARY] row's tint, applied to [RowSelectionColors.selectedBackground]
 * by the tone-aware [selectionBackground] below — deliberately far below a [RowSelectionTone.PRIMARY]
 * background, so a duplicate row of the selected feed reads as "same feed, not the row you're on"
 * rather than as a second selection. The same fraction of the same base color on every platform,
 * which is what makes this a shared constant rather than a per-platform [RowSelectionColors] field.
 */
internal const val SECONDARY_SELECTION_ALPHA = 0.15f

/**
 * A selected row's background in the pane that does *not* hold keyboard focus — [colors.selectedBackground]
 * unchanged on a platform whose [PaneFocusIndication] is [PaneFocusIndication.Ring] (Android: the
 * color itself never moves with pane focus, see that type's own KDoc), or dimmed to
 * [PaneFocusIndication.Dim.alpha] on a platform whose indication is [PaneFocusIndication.Dim]
 * (desktop: this dimming *is* how pane focus is shown, so it needs to actually differ from the
 * focused color).
 */
private fun nonFocusedSelectionBackground(colors: RowSelectionColors): Color = when (val focus = colors.paneFocus) {
    is PaneFocusIndication.Dim -> colors.selectedBackground.copy(alpha = focus.alpha)
    is PaneFocusIndication.Ring -> colors.selectedBackground
}

/**
 * A selected row's content color in the pane that does *not* hold keyboard focus — mirrors
 * [nonFocusedSelectionBackground]'s own per-[PaneFocusIndication] split. `null` on [PaneFocusIndication.Dim]
 * (desktop's dimmed background still has enough contrast with each element's own default color, so
 * it sets none — see [RowSelectionColors.selectedContent]'s own KDoc), [colors.selectedContent]
 * unchanged on [PaneFocusIndication.Ring] (Android: content color never moves with pane focus
 * either).
 */
private fun nonFocusedSelectionContent(colors: RowSelectionColors): Color? = when (colors.paneFocus) {
    is PaneFocusIndication.Dim -> null
    is PaneFocusIndication.Ring -> colors.selectedContent
}

/**
 * Background for a selectable row, taken from this platform's [RowSelectionColors]. Delegates to
 * the [RowSelectionTone] overload below ([selected] is exactly [RowSelectionTone.PRIMARY] vs.
 * [RowSelectionTone.NONE] — the two are mathematically equivalent for a row that never renders more
 * than one instance of itself, e.g. every article row).
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun selectionBackground(
    selected: Boolean,
    focused: Boolean,
    colors: RowSelectionColors = rowSelectionColors(),
): Color = selectionBackground(if (selected) RowSelectionTone.PRIMARY else RowSelectionTone.NONE, focused, colors)

/**
 * Content color to pair with [selectionBackground] — see the [RowSelectionTone] overload below,
 * which this delegates to the same way [selectionBackground] does.
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun selectionContentColorOrNull(
    selected: Boolean,
    focused: Boolean,
    colors: RowSelectionColors = rowSelectionColors(),
): Color? = selectionContentColorOrNull(if (selected) RowSelectionTone.PRIMARY else RowSelectionTone.NONE, focused, colors)

/**
 * Background for a row that can render as more than one instance (see [RowSelectionTone]):
 * [colors.selectedBackground] in the focused pane, [nonFocusedSelectionBackground] otherwise, for
 * [RowSelectionTone.PRIMARY]; a faint [SECONDARY_SELECTION_ALPHA] tint of [colors.selectedBackground]
 * for [RowSelectionTone.SECONDARY] regardless of focus (an echo of the selection, not a second one).
 * Transparent whenever [LocalRowSelectionVisible] reads `false` — set by `HomeScreen` at
 * [PaneLayout.Single], where "selected" doesn't mean "on screen" the way it does at
 * [PaneLayout.Dual]/[PaneLayout.Triple]: tapping a row navigates away from it, so a lingering
 * highlight on a row the user can no longer see would read as stale rather than as "your place."
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun selectionBackground(
    tone: RowSelectionTone,
    focused: Boolean,
    colors: RowSelectionColors = rowSelectionColors(),
): Color = when {
    !LocalRowSelectionVisible.current -> Color.Transparent
    tone == RowSelectionTone.PRIMARY -> if (focused) colors.selectedBackground else nonFocusedSelectionBackground(colors)
    tone == RowSelectionTone.SECONDARY -> colors.selectedBackground.copy(alpha = SECONDARY_SELECTION_ALPHA)
    else -> Color.Transparent
}

/**
 * Content color to pair with the tone-aware [selectionBackground] — only a
 * [RowSelectionTone.PRIMARY] row takes one, exactly as in the boolean overload (a
 * [RowSelectionTone.SECONDARY] echo is a faint tint that every element's default color still reads
 * against). Also gated on [LocalRowSelectionVisible].
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun selectionContentColorOrNull(
    tone: RowSelectionTone,
    focused: Boolean,
    colors: RowSelectionColors = rowSelectionColors(),
): Color? = when {
    !LocalRowSelectionVisible.current || tone != RowSelectionTone.PRIMARY -> null
    focused -> colors.selectedContent
    else -> nonFocusedSelectionContent(colors)
}

/**
 * The color a list row's outline decoration should paint, or `null` for no outline at all — the
 * judgment half of [listRowOutline], kept separate from the `Modifier`-returning half so it can be
 * unit-tested the same way [selectionContentColorOrNull] already is (a `Modifier`'s contents can't
 * be inspected by a test the way a `Color?` can). Delegates to the [RowSelectionTone] overload below
 * the same way [selectionBackground] does.
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun rowOutlineColorOrNull(
    selected: Boolean,
    focused: Boolean,
    dropTargetColor: Color? = null,
    colors: RowSelectionColors = rowSelectionColors(),
): Color? = rowOutlineColorOrNull(
    if (selected) RowSelectionTone.PRIMARY else RowSelectionTone.NONE,
    focused,
    dropTargetColor,
    colors,
)

/**
 * Tone-aware [rowOutlineColorOrNull] — see [rowOutlineColorOrNull]'s boolean overload for the
 * general contract. A non-null [dropTargetColor] always wins over the keyboard-focus ring below — a
 * drop target is a transient, high-urgency signal, and a row can only paint one outline at a time.
 * Otherwise, the ring shows only when **all** of these hold: [LocalRowSelectionVisible] is showing
 * highlights at all, [LocalKeyboardEngaged] has latched (see its own KDoc), this platform's
 * [PaneFocusIndication] is actually [PaneFocusIndication.Ring] (desktop's is [PaneFocusIndication.Dim]
 * — its dimming already carries pane focus, see that type's own KDoc), [focused] is true (this
 * row's pane holds keyboard focus), and [tone] is [RowSelectionTone.PRIMARY] — a row that is merely
 * a [RowSelectionTone.SECONDARY] echo of the selected feed never gets a focus ring, only the one
 * *actual* selected instance does.
 *
 * @param colors Overridable for tests only — production call sites always use the platform default.
 */
@Composable
internal fun rowOutlineColorOrNull(
    tone: RowSelectionTone,
    focused: Boolean,
    dropTargetColor: Color? = null,
    colors: RowSelectionColors = rowSelectionColors(),
): Color? = when {
    dropTargetColor != null -> dropTargetColor
    LocalRowSelectionVisible.current && LocalKeyboardEngaged.current && tone == RowSelectionTone.PRIMARY && focused ->
        (colors.paneFocus as? PaneFocusIndication.Ring)?.color
    else -> null
}

/**
 * A list row's outline decoration — a drop-target border, or (absent one) a keyboard-focus ring
 * around the row currently selected in the pane that holds keyboard focus. Traces [kind]'s own
 * [listRowShape] so the outline always matches the shape the row is clipped to, whichever it is.
 * See [rowOutlineColorOrNull] for the color it resolves.
 */
@Composable
internal fun listRowOutline(
    kind: ListRowKind,
    selected: Boolean,
    focused: Boolean,
    dropTargetColor: Color? = null,
    colors: RowSelectionColors = rowSelectionColors(),
): Modifier = rowOutlineColorOrNull(selected, focused, dropTargetColor, colors)?.let {
    Modifier.border(ROW_OUTLINE_WIDTH, it, listRowShape(kind))
} ?: Modifier

/** Tone-aware overload of [listRowOutline] — see [rowOutlineColorOrNull]'s own tone overload. */
@Composable
internal fun listRowOutline(
    kind: ListRowKind,
    tone: RowSelectionTone,
    focused: Boolean,
    dropTargetColor: Color? = null,
    colors: RowSelectionColors = rowSelectionColors(),
): Modifier = rowOutlineColorOrNull(tone, focused, dropTargetColor, colors)?.let {
    Modifier.border(ROW_OUTLINE_WIDTH, it, listRowShape(kind))
} ?: Modifier
