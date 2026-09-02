package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A determinate progress bar for an operation with a known fraction complete (an update download)
 * — [SmallSpinner]'s counterpart for when progress *can* be reported rather than merely "something
 * is happening". A single `commonMain` composable, no `expect`/`actual` needed — like
 * [SmallSpinner] wrapping `CircularProgressIndicator` directly, `LinearProgressIndicator` already
 * applies its own accessibility semantics for the reported [progress] internally.
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
    )
}
