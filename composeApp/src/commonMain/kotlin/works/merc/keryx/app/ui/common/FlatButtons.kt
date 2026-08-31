package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Primary/filled action button. `expect`/`actual` per platform: the desktop `actual` is a flat
 * replacement for M3's `Button`, built on plain `Modifier.clickable` (no `indication` override) so
 * presses use the app-wide flat [androidx.compose.foundation.LocalIndication] instead of M3's
 * hardcoded ripple (see `.claude/skills/ui-guidelines/SKILL.md`); the Android `actual` delegates to
 * M3's own `Button` to match Android's native visual language. Intentionally has no
 * `colors`/`contentColor` override params — no existing call site customizes colors.
 */
@Composable
expect fun FlatButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
)

/**
 * Tonal-filled secondary button (mirrors M3's `FilledTonalButton`). The desktop `actual`'s solid
 * `secondaryContainer` fill plus a hairline border reads as an obviously tactile "button" — unlike
 * a transparent outlined box, which can be mistaken for a link/label. Use for secondary actions
 * that still need clear button affordance (OPML import/export, Dropbox disconnect, update check,
 * setup cards); [FlatButton] stays the primary/filled action.
 *
 * @param destructive Paints the container `errorContainer`/`onErrorContainer` on both platforms, for
 *   an action that destroys data (see the `ui-guidelines` skill for why `errorContainer` and not
 *   the `error` role itself). Color is never the only signal — such a button also carries its own
 *   glyph and a confirmation dialog.
 */
@Composable
expect fun FlatTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable () -> Unit,
)

/** Bare, inline text button (mirrors M3's `TextButton`). */
@Composable
expect fun FlatTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
)
