package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_read
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_star
import works.merc.keryx.app.resources.article_unstar
import works.merc.keryx.app.resources.common_menu_item_with_shortcut
import works.merc.keryx.app.resources.home_notifications
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Displays the notifications button, unread notification count, and notification popup.
 */
/**
 * Displays the notifications button and opens the notification center popup when selected.
 *
 * @param notifVm The view model providing notification items and handling notification actions.
 */
@Composable
internal fun NotificationsBell(notifVm: NotificationCenterViewModel) {
    val notifications by notifVm.items.collectAsStateSafe(emptyList())
    var showNotifications by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box {
        val notificationsTooltip = stringResource(Res.string.home_notifications)
        TooltipIconButton(tooltip = notificationsTooltip, onClick = { showNotifications = !showNotifications }) {
            Box(contentAlignment = Alignment.TopEnd) {
                KeryxIcon(KeryxIcons.Notifications, contentDescription = notificationsTooltip)
                if (notifications.isNotEmpty()) {
                    Box(
                        Modifier
                            .offset(x = 6.dp, y = (-4).dp)
                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            notifications.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        if (showNotifications) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(x = 0, y = with(density) { 48.dp.roundToPx() }),
                onDismissRequest = { showNotifications = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                // Close the popover once a notification's action leads somewhere, so the destination
                // (feed list selection / settings dialog / explanation dialog) is actually visible.
                NotificationCenterSheet(notifVm, onNavigated = { showNotifications = false })
            }
        }
    }
}

internal data class ArticleRowMetrics(val rowHeight: Dp, val faviconSize: Dp)

/**
 * Computes consistent article-row dimensions from the current typography and density.
 *
 * @return The row height and favicon size for article rows.
 */
@Composable
internal fun rememberArticleRowMetrics(): ArticleRowMetrics {
    val density = LocalDensity.current
    val bodyLineHeight = MaterialTheme.typography.bodyMedium.lineHeight
    val labelLineHeight = MaterialTheme.typography.labelSmall.lineHeight
    return remember(density, bodyLineHeight, labelLineHeight) {
        val titleBlockHeight = with(density) { bodyLineHeight.toDp() } * 2
        val subtitleHeight = with(density) { labelLineHeight.toDp() }
        val contentHeight = titleBlockHeight + 2.dp + subtitleHeight
        ArticleRowMetrics(rowHeight = contentHeight, faviconSize = contentHeight * 0.6f)
    }
}

/**
 * Renders an article row with selection styling, read and starred indicators, metadata, and context-menu actions.
 *
 * @param article The article to display.
 * @param feedTitle The title of the article's feed.
 * @param feedFavicon The feed favicon URL, if available.
 * @param selected Whether the row is selected.
 * @param focused Whether the row has focus.
 * @param rowHeight The minimum height of the row.
 * @param faviconSize The display size of the feed favicon.
 * @param onClick Called when the row is clicked or its context menu is opened.
 * @param onToggleRead Called to toggle the article's read state.
 * @param onToggleStar Called to toggle the article's starred state.
 * @param onCopyUrl Called to copy the article URL.
 * @param onOpenInBrowser Called to open the article URL in a browser.
 * @param titleOverride An optional title to display instead of the article title.
 */
@Composable
internal fun ArticleRow(
    article: Articles,
    feedTitle: String,
    feedFavicon: String?,
    selected: Boolean,
    focused: Boolean,
    rowHeight: Dp,
    faviconSize: Dp,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleStar: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenInBrowser: () -> Unit,
    titleOverride: AnnotatedString? = null,
) {
    val unread = article.is_read == 0L
    val toggleReadLabel = stringResource(
        Res.string.common_menu_item_with_shortcut,
        stringResource(if (article.is_read == 1L) Res.string.article_mark_as_unread else Res.string.article_mark_as_read),
        "U",
    )
    val toggleStarLabel = stringResource(
        Res.string.common_menu_item_with_shortcut,
        stringResource(if (article.is_starred == 1L) Res.string.article_unstar else Res.string.article_star),
        "S",
    )
    val copyUrlLabel = stringResource(Res.string.common_menu_item_with_shortcut, stringResource(Res.string.article_copy_url), "C")
    val openInBrowserLabel = stringResource(Res.string.common_menu_item_with_shortcut, stringResource(Res.string.article_open_in_browser), "O")
    val noTitleFallback = stringResource(Res.string.article_no_title)
    Row(
        Modifier.testTag("article-${article.id}")
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(selectionBackground(selected, focused))
            .clickable(onClick = onClick)
            .nativeContextMenu(
                items = {
                    buildList {
                        add(NativeMenuItem(toggleStarLabel) { onToggleStar() })
                        add(NativeMenuItem(toggleReadLabel) { onToggleRead() })
                        if (article.url.isNotBlank()) {
                            add(NativeMenuItem(copyUrlLabel) { onCopyUrl() })
                            add(NativeMenuItem(openInBrowserLabel) { onOpenInBrowser() })
                        }
                    }
                },
                onOpen = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .heightIn(min = rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp).height(rowHeight)) {
            if (article.is_starred == 1L) {
                KeryxIcon(
                    KeryxIcons.Star,
                    contentDescription = null,
                    tint = StarredColor,
                    modifier = Modifier.requiredSize(14.dp).align(Alignment.TopCenter),
                )
            }
            if (unread) {
                Box(
                    Modifier
                        .size(8.dp)
                        .align(Alignment.Center)
                        .background(selectionContentColorOrNull(selected, focused) ?: MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        // TEMPORARY WORKAROUND, not the preferred structure: padding here (and on the Column/Row
        // below) folds gaps that used to be self-documenting Spacer siblings into the padding of
        // an unrelated element, and duplicates the 6dp gap across the AsyncImage/Spacer branches
        // below. This row is close to a LazyColumn reuse-pool crash threshold that scales with
        // per-item LayoutNode count (see docs/known-issues.md), so it trades that readability for
        // fewer nodes. Once the upstream Compose bug is fixed (docs/known-issues.md "Re-checking
        // after a library update"), revert this whole ArticleRow body to Spacer-separated gaps
        // and a Box-wrapped favicon — see the "cut ArticleRow's LazyColumn item node count" commit
        // (582f0a8) for the pre-mitigation structure to restore.
        if (!feedFavicon.isNullOrBlank()) {
            AsyncImage(
                model = feedFavicon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(KeryxIcons.PublicFilled),
                modifier = Modifier.padding(start = 6.dp).size(faviconSize).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Spacer(Modifier.padding(start = 6.dp).size(faviconSize))
        }
        val selectionContentColor = selectionContentColorOrNull(selected, focused)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                titleOverride ?: AnnotatedString(article.title.ifBlank { noTitleFallback }),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                color = selectionContentColor?.let { if (unread) it else it.copy(alpha = 0.85f) }
                    ?: if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val metaColor = selectionContentColor?.copy(alpha = 0.7f)
                ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            // Feed title and timestamp are separate Texts rather than one joined string: sharing a
            // single ellipsis budget let a long feed title truncate the timestamp away entirely.
            // Only the title carries the weight, so Row measures the timestamp at its full intrinsic
            // width first and the title ellipsizes into whatever is left.
            Row(Modifier.padding(top = 2.dp).fillMaxWidth()) {
                Text(
                    feedTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatTimestamp(article.published_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
