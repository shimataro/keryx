package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.DrawableResource

/**
 * An icon with a count badge overlaid on its corner (currently only [NotificationsBell]'s bell) — a
 * hand-rolled pill on desktop, M3's own `BadgedBox`/`Badge` on Android, rather than one shared look.
 * No badge is drawn when [count] is `0`.
 */
@Composable
internal expect fun KeryxBadgedIcon(icon: DrawableResource, contentDescription: String, count: Int)
