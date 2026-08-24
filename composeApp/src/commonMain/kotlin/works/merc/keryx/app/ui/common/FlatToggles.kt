package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Boolean on/off toggle switch. `expect`/`actual` per platform: the desktop `actual` is a flat
 * replacement for M3's `Switch`, built on plain `Modifier.toggleable` (no `indication` override) so
 * presses use the app-wide flat [androidx.compose.foundation.LocalIndication] instead of M3's
 * hardcoded ripple — same visual language as [SegmentedControl]/[ToggleChip] (hairline
 * `outlineVariant` border, `primary` fill when on, `onPrimary` content). See
 * `.claude/skills/ui-guidelines/SKILL.md`. The Android `actual` delegates to M3's own `Switch` to
 * match Android's native visual language and touch-target sizing.
 */
@Composable
expect fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

/**
 * Boolean checkbox. `expect`/`actual` per platform, same rationale as [FlatSwitch]: the desktop
 * `actual` is a flat replacement for M3's `Checkbox`, the Android `actual` delegates to M3's own
 * `Checkbox`.
 */
@Composable
expect fun FlatCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)
