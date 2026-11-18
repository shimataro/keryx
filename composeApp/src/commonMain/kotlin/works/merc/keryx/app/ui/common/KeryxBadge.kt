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

/**
 * The count pill alone — the same pill [KeryxBadgedIcon] overlays on its icon (a hand-rolled pill on
 * desktop, M3's own `Badge` on Android), for a row that shows the same count beside its label (the
 * notification center's row, so it visibly matches the drawer button's badge). Draws nothing when
 * [count] is `0`. Unlike [KeryxBadgedIcon]'s badge, the number stays in the semantics tree, since a
 * row's own label says what it counts.
 */
@Composable
internal expect fun KeryxCountBadge(count: Int)
