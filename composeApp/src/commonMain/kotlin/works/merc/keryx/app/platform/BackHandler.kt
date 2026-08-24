package works.merc.keryx.app.platform

import androidx.compose.runtime.Composable

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
