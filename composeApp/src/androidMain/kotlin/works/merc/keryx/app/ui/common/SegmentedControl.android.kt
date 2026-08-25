package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/** Slot ids for [SegmentedControl]'s two subcomposition passes — see its own KDoc. */
private enum class SegmentedControlPass { Measure, Place }

/**
 * The row [SegmentedControl] renders, twice — see its KDoc. [uniformMinHeight] is `null` on the
 * measuring pass (every button at its natural height) and the tallest button's measured height on
 * the placing pass (every button floored to it).
 */
@Composable
private fun <T> SegmentedButtonRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    uniformMinHeight: Dp?,
) {
    SingleChoiceSegmentedButtonRow {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label) },
                icon = {},
                modifier = uniformMinHeight?.let { Modifier.heightIn(min = it) } ?: Modifier,
            )
        }
    }
}

/**
 * Android `actual`: delegates to M3's own `SingleChoiceSegmentedButtonRow`/`SegmentedButton` and
 * `FilterChip`, which already pick up [KeryxTheme]'s `colorScheme` with no color overrides needed
 * here — same "plain M3" approach as `KeryxTextField.android.kt`/`KeryxDialogs.android.kt`. Both
 * `SegmentedButton` and `FilterChip` are stable (non-experimental) APIs as of this app's
 * `compose-material3` version.
 *
 * The [SubcomposeLayout] exists to give every button the tallest one's height. M3's own row does
 * not do this: `SingleChoiceSegmentedButtonRow` hardcodes `verticalAlignment =
 * Alignment.CenterVertically` with no parameter to override it, so a button whose label wraps to
 * two lines (e.g. "System" beside "Light"/"Dark" at phone width) is simply taller than its
 * single-line siblings, which then float centered in a ragged row. The component is designed
 * around a fixed-height control — `OutlinedSegmentedButtonTokens.ContainerHeight` is 40dp — so
 * wrapping labels are outside what it accounts for, and neither Material 3's guidelines nor the
 * Compose API offer a supported way to equalize the heights.
 *
 * **Intrinsic measurement cannot solve this**, which is why the usual
 * `Modifier.height(IntrinsicSize.Min)` + `fillMaxHeight()` pattern is deliberately not used (it was
 * tried first and clipped the wrapped label's second line on a real device). `Row.minIntrinsicHeight`
 * distributes width to weighted children using their *max* intrinsic widths — for a `Text`, that is
 * its full single-line width — so each label is asked "how tall are you?" at a width it will never
 * actually get, answers "one line", and the row settles on a height too short for the label's real
 * two-line layout. M3 compounds this internally: `SegmentedButtonContentMeasurePolicy` doesn't
 * override the intrinsic-height calculation at all, and `SegmentedButtonContent` itself wraps its
 * content in another `Layout(Modifier.height(IntrinsicSize.Min))`. This is the same
 * intrinsic-vs-real measurement-pass disagreement the `ui-guidelines` skill records for
 * `ArticleRow`, here originating inside M3's own internals where there is no override point.
 *
 * Two real measurement passes sidestep intrinsics entirely: pass one measures the row at the real
 * incoming [androidx.compose.ui.unit.Constraints] and reads back its height (a `Row`'s height is
 * already its tallest child's), pass two re-composes the same row with that height applied as each
 * button's `heightIn(min = …)`. Both passes see identical width constraints, so both wrap labels
 * identically and the second can only ever raise the shorter buttons to match — never clip the
 * tallest. Unlike `minLines`, this is correct for any line count and adds no wasted height when
 * every label happens to fit on one line.
 *
 * The cost is composing the buttons twice (the measuring pass stays in composition, unplaced and
 * therefore never drawn or hit-tested). That is acceptable here — this renders a handful of options
 * on the settings screen, not inside a `LazyColumn` — but is why this is not a pattern to reach for
 * generally.
 */
@Composable
actual fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SubcomposeLayout { constraints ->
        // SingleChoiceSegmentedButtonRow uses width(IntrinsicSize.Min), so without intervention
        // it shrinks to the narrowest width that satisfies its intrinsic measurement. For long
        // labels this squeezes text vertically. Force the row to use the full available width so
        // each segment shares the parent width equally.
        val fullWidthConstraints = if (constraints.maxWidth == Constraints.Infinity) {
            constraints
        } else {
            constraints.copy(minWidth = constraints.maxWidth)
        }

        val natural = subcompose(SegmentedControlPass.Measure) {
            SegmentedButtonRow(options, selected, onSelect, uniformMinHeight = null)
        }.first().measure(fullWidthConstraints)

        val uniform = subcompose(SegmentedControlPass.Place) {
            SegmentedButtonRow(options, selected, onSelect, uniformMinHeight = natural.height.toDp())
        }.first().measure(fullWidthConstraints)

        layout(
            width = if (constraints.maxWidth == Constraints.Infinity) uniform.width else constraints.maxWidth,
            height = uniform.height,
        ) {
            uniform.place(0, 0)
        }
    }
}

@Composable
actual fun ToggleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    FilterChip(
        selected = checked,
        onClick = { onCheckedChange(!checked) },
        label = { Text(label) },
        enabled = enabled,
    )
}
