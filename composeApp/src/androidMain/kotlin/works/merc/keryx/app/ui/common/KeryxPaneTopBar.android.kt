package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * Android `actual`: a real M3 [TopAppBar] — see the `expect`'s KDoc in `commonMain`.
 *
 * `windowInsets = WindowInsets(0)`: `HomeScreen`'s `Scaffold` (no `topBar` of its own) already
 * reserves the full system-bar inset as content padding before any pane is composed, so letting
 * this `TopAppBar` fall back to its own default `windowInsets` would consume the same inset a
 * second time, pushing every pane's header well below the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxPaneTopBar(
    modifier: Modifier,
    title: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = {
            if (title != null) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
