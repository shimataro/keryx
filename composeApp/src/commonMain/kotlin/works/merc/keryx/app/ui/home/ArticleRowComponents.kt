package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.input.key.Key
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeMenuShortcut
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_read
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_star
import works.merc.keryx.app.resources.article_unstar
import works.merc.keryx.app.resources.home_notifications
import works.merc.keryx.app.ui.common.KeryxAnchoredPanel
import works.merc.keryx.app.ui.common.KeryxBadgedIcon
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Displays the notifications button, unread notification count, and notification popup.
 *
 * @param notifVm The view model providing notification items and handling notification actions.
 */
@Composable
internal fun NotificationsBell(notifVm: NotificationCenterViewModel) {
    val notifications by notifVm.items.collectAsStateSafe(emptyList())
    var showNotifications by remember { mutableStateOf(false) }
    Box {
        val notificationsTooltip = stringResource(Res.string.home_notifications)
        TooltipIconButton(tooltip = notificationsTooltip, onClick = { showNotifications = !showNotifications }) {
            KeryxBadgedIcon(KeryxIcons.Notifications, contentDescription = notificationsTooltip, count = notifications.size)
        }
        if (showNotifications) {
            KeryxAnchoredPanel(
                onDismissRequest = { showNotifications = false },
                alignment = Alignment.TopEnd,
                anchorOffsetY = 48.dp,
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

/** The article-row strings that do not vary per article, resolved once for a whole list. */
internal data class ArticleRowStrings(
    val markAsRead: String,
    val markAsUnread: String,
    val star: String,
    val unstar: String,
    val copyUrl: String,
    val openInBrowser: String,
    val noTitleFallback: String,
    val zone: TimeZone,
)

/**
 * Resolves localized article-row labels, fallback text, and the system time zone for reuse across a list.
 *
 * @return The shared article-row strings and time zone.
 */
@Composable
internal fun rememberArticleRowStrings(): ArticleRowStrings {
    val markAsRead = stringResource(Res.string.article_mark_as_read)
    val markAsUnread = stringResource(Res.string.article_mark_as_unread)
    val star = stringResource(Res.string.article_star)
    val unstar = stringResource(Res.string.article_unstar)
    val copyUrl = stringResource(Res.string.article_copy_url)
    val openInBrowser = stringResource(Res.string.article_open_in_browser)
    val noTitleFallback = stringResource(Res.string.article_no_title)
    // Resolved once with the strings rather than per row. The keys below never change for a
    // time-zone change, so a zone switched while Keryx is running (it is tray-resident, so that can
    // be days) is only picked up when this composition is recreated. Accepted: the alternative is
    // TimeZone.currentSystemDefault() — which clones the JVM default zone — per visible row per
    // composition, and article timestamps are not a clock.
    return remember(markAsRead, markAsUnread, star, unstar, copyUrl, openInBrowser, noTitleFallback) {
        ArticleRowStrings(
            markAsRead = markAsRead,
            markAsUnread = markAsUnread,
            star = star,
            unstar = unstar,
            copyUrl = copyUrl,
            openInBrowser = openInBrowser,
            noTitleFallback = noTitleFallback,
            zone = TimeZone.currentSystemDefault(),
        )
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
 * @param strings The per-list strings and time zone, hoisted above `items {}` by the caller.
 */
@Composable
internal fun ArticleRow(
    article: ArticleListRow,
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
    strings: ArticleRowStrings = rememberArticleRowStrings(),
) {
    val unread = article.is_read == 0L
    val toggleReadLabel = if (article.is_read == 1L) strings.markAsUnread else strings.markAsRead
    val toggleStarLabel = if (article.is_starred == 1L) strings.unstar else strings.star
    val copyUrlLabel = strings.copyUrl
    val openInBrowserLabel = strings.openInBrowser
    val noTitleFallback = strings.noTitleFallback
    val testTag = remember(article.id) { "article-${article.id}" }
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag(testTag)
            .fillMaxWidth()
            .listRowClickable(rowInteraction, selected, onClick)
            .nativeContextMenu(
                items = {
                    val urlUsable = hasUsableUrl(article.url)
                    listOf(
                        NativeMenuItem(toggleStarLabel, NativeMenuShortcut(Key.S, ctrl = true, shift = true)) { onToggleStar() },
                        NativeMenuItem(toggleReadLabel, NativeMenuShortcut(Key.U, ctrl = true, shift = true)) { onToggleRead() },
                        NativeMenuItem(copyUrlLabel, NativeMenuShortcut(Key.C, ctrl = true, shift = true), enabled = urlUsable) { onCopyUrl() },
                        NativeMenuItem(
                            openInBrowserLabel,
                            NativeMenuShortcut(Key.O, ctrl = true, shift = true),
                            enabled = urlUsable,
                        ) { onOpenInBrowser() },
                    )
                },
                onOpen = onClick,
            )
            .listRowSurface(selectionBackground(selected, focused), ListRowKind.ListItem, rowInteraction)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .heightIn(min = maxOf(rowHeight, listRowMinHeight())),
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
                modifier = Modifier.padding(start = 6.dp).size(faviconSize).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
        } else {
            Spacer(
                Modifier.padding(start = 6.dp).size(faviconSize).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
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
                    remember(strings.zone, article.published_at) {
                        formatTimestamp(article.published_at, strings.zone)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = metaColor,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
