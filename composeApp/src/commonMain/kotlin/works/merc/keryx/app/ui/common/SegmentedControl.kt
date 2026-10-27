package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable

/**
 * A single-select control. `expect`/`actual` per platform: the desktop `actual` renders a
 * native-looking bordered, filled-background segmented row rather than a horizontally-scrolling
 * row of pill chips — segments size to their label content (`Modifier.weight(1f)` is deliberately
 * not used) so labels never wrap/truncate. The Android `actual` delegates to M3's own
 * `SingleChoiceSegmentedButtonRow` + `SegmentedButton` to match Android's native visual language.
 */
@Composable
expect fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit)

/**
 * A pop-up button rendered with the same visual language as [SegmentedControl]'s selected segment
 * on desktop: [label] names the current choice, a trailing chevron says it opens a menu, and
 * [onClick] opens it (typically `NativeMenuAnchor`'s `open`). [highlighted] fills it as a checked
 * chip, for a choice that narrows what is shown — `ArticleListPane`'s list mode (`HomeViewModel.mode`)
 * whenever it is not "All". `expect`/`actual` per platform: the Android `actual` is M3's own
 * `FilterChip` with a dropdown arrow, M3's chip-with-menu idiom.
 *
 * @param label The current choice, shown on the chip.
 * @param accessibilityLabel What the chip chooses, announced with [label] as its state.
 */
@Composable
expect fun MenuChip(label: String, accessibilityLabel: String, highlighted: Boolean, onClick: () -> Unit)
