package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

/** No-op: desktop has no back gesture/button — see the `expect`'s KDoc. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/** No-op: desktop has no back gesture/button — see the `expect`'s KDoc. */
@Composable
actual fun PredictiveBackHandler(enabled: Boolean, onBack: suspend (progress: Flow<BackProgress>) -> Unit) = Unit
