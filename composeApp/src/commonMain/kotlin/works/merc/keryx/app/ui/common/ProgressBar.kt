package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A determinate progress bar for an operation with a known fraction complete (an update download)
 * — [SmallSpinner]'s counterpart for when progress *can* be reported rather than merely "something
 * is happening". A single `commonMain` composable, no `expect`/`actual` needed — like
 * [SmallSpinner] wrapping `CircularProgressIndicator` directly, `LinearProgressIndicator` already
 * applies its own accessibility semantics for the reported [progress] internally.
 *
 * `gapSize = 0.dp` and an empty `drawStopIndicator` turn off two M3-expressive decorations
 * (`ProgressIndicatorDefaults.LinearIndicatorTrackGapSize`'s track/indicator gap, and the small dot
 * drawn at the track's end) — confirmed by rendering this composable at `UpdatesTabTest`'s own
 * `Downloading` state and inspecting the output: both were visible and read as a stray gap/dot
 * against this app's flat design language (see `docs/external-spec.md` §9), not as an M3 element
 * anything else in this app matches. No `expect`/`actual` split was needed for this — unlike
 * [FlatButton] and friends, which replace M3's whole widget with a flat one on desktop, this only
 * turns off two of *this* widget's own optional decorations, on every platform equally.
 *
 * @param progress Current fraction complete. Clamped to `0f..1f` — a caller computing this from
 *   raw byte counts shouldn't have to guard against a transient one-chunk overshoot itself.
 */
@Composable
fun KeryxLinearProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}
