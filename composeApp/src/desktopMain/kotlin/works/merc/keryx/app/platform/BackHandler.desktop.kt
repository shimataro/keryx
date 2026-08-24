package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable

/** No-op: desktop has no back gesture/button — see the `expect`'s KDoc. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
