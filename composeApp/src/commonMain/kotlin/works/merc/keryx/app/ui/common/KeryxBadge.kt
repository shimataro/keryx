package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.DrawableResource

/**
 * An icon with a count badge overlaid on its corner (currently only the narrow layout's drawer button,
 * carrying the notification center's count — see `ArticleListTopBar`) — a
 * hand-rolled pill on desktop, M3's own `BadgedBox`/`Badge` on Android, rather than one shared look.
 * No badge is drawn when [count] is `0`. The badge itself is hidden from accessibility services, so
 * [contentDescription] must say what the count is ("Open feed list, 3 notifications") — a bare
 * number read after the icon's name would not say what it counts.
 */
@Composable
internal expect fun KeryxBadgedIcon(icon: DrawableResource, contentDescription: String, count: Int)
