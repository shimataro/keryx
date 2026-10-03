package works.merc.keryx.app.platform

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler as AndroidXBackHandler
import androidx.activity.compose.PredictiveBackHandler as AndroidXPredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Delegates to `androidx.activity.compose.BackHandler` (already a dependency via
 * `activity-compose`). */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = AndroidXBackHandler(enabled, onBack)

/**
 * Delegates to `androidx.activity.compose.PredictiveBackHandler`, translating each
 * [BackEventCompat] into a [BackProgress]. `map` keeps the source flow's completion and
 * cancellation intact, which is how the commit/cancel outcome reaches `onBack`.
 */
@Composable
actual fun PredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<BackProgress>) -> Unit) =
    AndroidXPredictiveBackHandler(enabled) { events ->
        onBack(events.map { BackProgress(it.progress, it.swipeEdge == BackEventCompat.EDGE_RIGHT) })
    }
