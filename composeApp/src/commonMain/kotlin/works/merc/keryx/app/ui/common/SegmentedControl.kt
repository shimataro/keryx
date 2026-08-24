package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable

/**
 * A single-select control replacing Material3's `FilterChip` row (originally `ChipRow` in the
 * settings screen). `expect`/`actual` per platform: the desktop `actual` renders a native-looking
 * bordered, filled-background segmented row rather than a horizontally-scrolling row of pill chips
 * — segments size to their label content (`Modifier.weight(1f)` is deliberately not used) so labels
 * never wrap/truncate. The Android `actual` delegates to M3's own `SingleChoiceSegmentedButtonRow`
 * + `SegmentedButton` to match Android's native visual language.
 */
@Composable
expect fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit)

/**
 * A single boolean toggle rendered with the same visual language as [SegmentedControl]'s selected
 * segment on desktop — replaces Material3's `FilterChip` for standalone on/off controls (e.g.
 * `ArticleListPane`'s "unread only" toggle). `expect`/`actual` per platform, same rationale as
 * [SegmentedControl]: the Android `actual` delegates to M3's own `FilterChip`.
 */
@Composable
expect fun ToggleChip(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true)
