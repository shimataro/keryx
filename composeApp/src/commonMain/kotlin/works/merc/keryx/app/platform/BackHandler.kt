package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/**
 * Intercepts the platform's "back" gesture/button while [enabled], invoking [onBack] instead of
 * the platform default (closing the app, or — on Android — popping the activity back stack).
 *
 * Used by `HomeScreen` to pop the narrow-width navigation stack (see `ui/home/HomePaneLayout.kt`)
 * one level instead of exiting the app. On desktop there is no such gesture, so the `actual` is a
 * no-op: `HomeScreen` only ever disables this at [PaneLayout.Triple][works.merc.keryx.app.ui.home.PaneLayout.Triple]
 * anyway, which desktop always resolves to (see `TRIPLE_PANE_MIN_WIDTH`'s KDoc), but the no-op
 * keeps the `expect`/`actual` pair total rather than relying on that call-site discipline alone.
 */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * One progress update of an in-flight predictive back gesture (see [PredictiveBackHandler]).
 *
 * @param progress How far the gesture has travelled, from `0f` (just started) to `1f`, as the
 *   platform reports it.
 * @param fromRightEdge Whether the swipe started at the right screen edge (moving leftwards);
 *   `false` for the left edge.
 */
data class BackProgress(val progress: Float, val fromRightEdge: Boolean)

/**
 * Like [BackHandler], but also observes the gesture *before* it is committed, so the screen can
 * preview where back will lead while the finger is still down (Android's predictive back).
 *
 * [onBack] runs once per back action, receiving that action's progress events. The [Flow]
 * completing normally means the back was committed; it throwing a
 * [kotlinx.coroutines.CancellationException] means the gesture was cancelled, and [onBack] must
 * let that exception propagate. A back that has no gesture to preview (a 3-button navigation bar,
 * a hardware key, an OS without predictive back) completes the flow without any event.
 *
 * Desktop has no back gesture at all, so its `actual` is a no-op, like [BackHandler]'s.
 */
@Composable
expect fun PredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<BackProgress>) -> Unit)
