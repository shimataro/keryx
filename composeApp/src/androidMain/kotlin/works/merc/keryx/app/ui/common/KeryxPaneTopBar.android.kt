package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * Android `actual`: a real M3 [TopAppBar] — see the `expect`'s KDoc in `commonMain`.
 *
 * `windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)`: `HomeScreen`'s `Scaffold`
 * draws content edge-to-edge (`contentWindowInsets = WindowInsets(0)`) and only reserves a
 * horizontal inset of its own, so each pane's own header applies its own top inset here instead —
 * the bottom/horizontal sides are left to whichever element actually sits at that edge (a
 * `LazyColumn`'s `contentPadding`, or this same `Horizontal` inset the root `Box` already applies).
 * Consuming the full `WindowInsets` default here would double it up with that root inset.
 *
 * Transparent container: M3's `TopAppBar` paints `colorScheme.surface` by default, but each pane is
 * tinted with its own tonal role (`surfaceContainerLow` for the feed list, `surfaceContainer` for the
 * article list, `surface` for the reader — see the ui-guidelines skill's "Pane structure & tonal
 * roles"), so an opaque default would draw a mismatched band across the top of the first two. Being
 * transparent, the bar always takes the tone of the pane it heads. No scroll behavior is attached,
 * so the scrolled-under color never applies; it is made transparent too for the same reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxPaneTopBar(
    modifier: Modifier,
    title: String?,
    titleContent: (@Composable () -> Unit)?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = {
            titleContent?.invoke()
                ?: title?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        actions = actions,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}
