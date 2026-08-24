package works.merc.keryx.app.platform

import androidx.activity.compose.BackHandler as AndroidXBackHandler
import androidx.compose.runtime.Composable

/** Delegates to `androidx.activity.compose.BackHandler` (already a dependency via
 * `activity-compose`). */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = AndroidXBackHandler(enabled, onBack)
