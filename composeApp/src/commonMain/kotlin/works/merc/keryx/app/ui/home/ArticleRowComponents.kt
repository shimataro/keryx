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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.platform.NativeMenuEntry
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeMenuSeparator
import works.merc.keryx.app.platform.NativeMenuShortcut
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.presentation.formatTimestamp
import works.merc.keryx.app.presentation.home.articleReadAfterContextMenuOpen
import works.merc.keryx.app.presentation.home.canOpenInBrowser
import works.merc.keryx.app.presentation.home.canShareArticleUrl
import works.merc.keryx.app.presentation.home.hasUsableUrl
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_read
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_share
import works.merc.keryx.app.resources.article_state_starred
import works.merc.keryx.app.resources.article_state_unread
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
    val notifications by notifVm.items.collectAsState()
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
    val share: String,
    val noTitleFallback: String,
    val zone: TimeZone,
    /** The row's spoken "unread" state (see [articleRowStateDescription]). */
    val stateUnread: String,
    /** The row's spoken "starred" state (see [articleRowStateDescription]). */
    val stateStarred: String,
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
    val share = stringResource(Res.string.article_share)
    val noTitleFallback = stringResource(Res.string.article_no_title)
    val stateUnread = stringResource(Res.string.article_state_unread)
    val stateStarred = stringResource(Res.string.article_state_starred)
    // Resolved once with the strings rather than per row. The keys below never change for a
    // time-zone change, so a zone switched while Keryx is running (it is tray-resident, so that can
    // be days) is only picked up when this composition is recreated. Accepted: the alternative is
    // TimeZone.currentSystemDefault() — which clones the JVM default zone — per visible row per
    // composition, and article timestamps are not a clock.
    return remember(markAsRead, markAsUnread, star, unstar, copyUrl, openInBrowser, share, noTitleFallback, stateUnread, stateStarred) {
        ArticleRowStrings(
            markAsRead = markAsRead,
            markAsUnread = markAsUnread,
            star = star,
            unstar = unstar,
            copyUrl = copyUrl,
            openInBrowser = openInBrowser,
            share = share,
            noTitleFallback = noTitleFallback,
            zone = TimeZone.currentSystemDefault(),
            stateUnread = stateUnread,
            stateStarred = stateStarred,
        )
    }
}

/**
 * The article row's context-menu entries, labelled from the read state the article has **once the
 * right-click's own `onOpen` has run** — not from [article]'s snapshot alone.
 *
 * On desktop, right-clicking an unselected row selects it, which marks it read (external-spec §7),
 * but [article] is still the pre-selection snapshot when the menu is built (no recomposition happens
 * in between). Labelling from it would offer "Mark as read" for an article that is already read by
 * the time the menu appears, disagreeing with what ⌘⇧U would do right now. [selectedByOpen] carries
 * that side effect into the label. Each item then requests the explicit state its label promises
 * rather than toggling, so the click does exactly what was displayed.
 *
 * @param article The row's article as last composed.
 * @param selectedByOpen Whether this right-click selected the row (and so marked it read).
 * @param strings The per-list labels.
 * @param onSetRead Called with the read state the read item promises.
 * @param onSetStarred Called with the starred state the star item promises.
 * @param onCopyUrl Called to copy the article URL.
 * @param onOpenInBrowser Called to open the article URL in a browser.
 * @param onShare Called to share the article URL ([ArticleSharer.share]); `null` where the platform
 *   has no share sheet, which leaves the item out entirely (a per-platform constant, so the menu's
 *   shape stays stable at any one call site).
 * @return The menu entries, in the app menu bar's Article-menu order, then Share (which no menu bar
 *   carries).
 */
internal fun articleRowMenuEntries(
    article: ArticleListRow,
    selectedByOpen: Boolean,
    strings: ArticleRowStrings,
    onSetRead: (Boolean) -> Unit,
    onSetStarred: (Boolean) -> Unit,
    onCopyUrl: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: (() -> Unit)? = null,
): List<NativeMenuEntry> {
    val read = articleReadAfterContextMenuOpen(isRead = article.is_read == 1L, selectedByOpen = selectedByOpen)
    val starred = article.is_starred == 1L
    // Copy, open and share have their own rules, shared with every other route to each: any
    // non-blank URL can be copied or shared, but only an http(s) one is opened.
    val copyEnabled = hasUsableUrl(article.url)
    val openEnabled = canOpenInBrowser(article.url)
    val shareEnabled = canShareArticleUrl(article.url)
    return listOfNotNull(
        NativeMenuItem(
            if (read) strings.markAsUnread else strings.markAsRead,
            NativeMenuShortcut(Key.U, ctrl = true, shift = true),
        ) { onSetRead(!read) },
        NativeMenuItem(
            if (starred) strings.unstar else strings.star,
            NativeMenuShortcut(Key.S, ctrl = true, shift = true),
        ) { onSetStarred(!starred) },
        NativeMenuSeparator,
        NativeMenuItem(strings.openInBrowser, NativeMenuShortcut(Key.O, ctrl = true, shift = true), enabled = openEnabled) {
            onOpenInBrowser()
        },
        NativeMenuItem(strings.copyUrl, NativeMenuShortcut(Key.C, ctrl = true, shift = true), enabled = copyEnabled) {
            onCopyUrl()
        },
        onShare?.let { NativeMenuItem(strings.share, enabled = shareEnabled) { it() } },
    )
}

/**
 * What opening an [ArticleRow]'s context menu does before the menu appears: an unselected row is
 * [select]ed (which marks it read and activates the article list pane, like a click), while an
 * already-selected row only [activate]s the pane — re-selecting it would mark read again an article
 * the user just marked unread from this very menu or ⌘⇧U, but keyboard focus must still follow the
 * right-click into this pane.
 *
 * @param selected Whether the row was selected when the menu was opened.
 * @param select Selects the row (and activates the pane).
 * @param activate Activates the article list pane without changing the selection.
 * @return Whether this open selected the row — [articleRowMenuEntries]'s `selectedByOpen`.
 */
internal fun articleRowContextMenuOpen(selected: Boolean, select: () -> Unit, activate: () -> Unit): Boolean {
    if (selected) activate() else select()
    return !selected
}

/**
 * The state a screen reader announces for an article row, or `null` when there is none to announce.
 *
 * The row shows its unread and starred state only visually — an unlabelled dot and a star icon with
 * no content description — so without this TalkBack reads a row's title, feed and date but never
 * whether it is unread or starred. Unread comes first, then starred, joined with ", " — the same
 * order and separator as the SwiftUI row's `stateAccessibilityValue`.
 */
internal fun articleRowStateDescription(
    unread: Boolean,
    starred: Boolean,
    unreadLabel: String,
    starredLabel: String,
): String? = listOfNotNull(unreadLabel.takeIf { unread }, starredLabel.takeIf { starred })
    .joinToString(", ")
    .ifEmpty { null }

/**
 * What the most recent context-menu open did to an [ArticleRow]'s selection, carried from the menu's
 * `onOpen` to its `items()`. A plain mutable holder, deliberately not snapshot state — see its use
 * in [ArticleRow].
 */
private class ContextMenuOpenSelection {
    /** Whether that open selected the row (and so marked its article read). */
    var selectedByOpen = false
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
 * @param onClick Called when the row is clicked, or its context menu is opened while it is unselected.
 * @param onSetRead Called with the read state the context menu's read item promises.
 * @param onSetStarred Called with the starred state the context menu's star item promises.
 * @param onCopyUrl Called to copy the article URL.
 * @param onOpenInBrowser Called to open the article URL in a browser.
 * @param onShare Called to share the article URL; `null` (no share sheet on this platform) leaves the
 *   context menu's Share item out.
 * @param onActivate Called when the context menu is opened on the already-selected row, to move
 *   keyboard focus to this pane without re-selecting it (see [articleRowContextMenuOpen]).
 * @param titleOverride An optional title to display instead of the article title.
 * @param strings The per-list strings and time zone, hoisted above `items {}` by the caller.
 * @param ripplePulse A nonzero value plays a one-shot [playPulseRipple] on [interactionSource] —
 *   see `ArticleListPane.kt`'s `ripplePulseFor`. `0` (the default) never plays one.
 * @param interactionSource The row's press/selection interaction source. Hoisted (rather than
 *   created internally) so [ripplePulse] can be exercised directly in tests.
 * @param isTouchPrimary Whether to expose the row's unread/starred state as a semantics
 *   `stateDescription` (see [articleRowStateDescription]). Defaults to the platform's own value;
 *   a parameter so the Android path can be exercised in desktop tests.
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
    onSetRead: (Boolean) -> Unit,
    onSetStarred: (Boolean) -> Unit,
    onCopyUrl: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: (() -> Unit)? = null,
    onActivate: () -> Unit = {},
    titleOverride: AnnotatedString? = null,
    strings: ArticleRowStrings = rememberArticleRowStrings(),
    ripplePulse: Int = 0,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    val unread = article.is_read == 0L
    // Whether the most recent right-click selected this row (desktop only — Android never calls
    // onOpen). A plain holder rather than snapshot state: it is written by onOpen and read by the
    // menu's items() immediately afterwards, in the same pointer handler and before any
    // recomposition, so nothing in composition ever reads it.
    val openSelection = remember { ContextMenuOpenSelection() }
    val noTitleFallback = strings.noTitleFallback
    val testTag = remember(article.id) { "article-${article.id}" }
    PulseRippleEffect(ripplePulse, interactionSource)
    // Touch-primary (Android/TalkBack) only, so desktop's semantics stay exactly as they were. Merged
    // into the row's own clickable node, so it is announced together with the row.
    val stateDescriptionText = if (isTouchPrimary) {
        articleRowStateDescription(unread, article.is_starred == 1L, strings.stateUnread, strings.stateStarred)
    } else {
        null
    }
    Row(
        Modifier.testTag(testTag)
            .then(
                if (stateDescriptionText != null) {
                    Modifier.semantics { stateDescription = stateDescriptionText }
                } else {
                    Modifier
                },
            )
            .fillMaxWidth()
            .listRowClickable(interactionSource, selected, onClick)
            .nativeContextMenu(
                items = {
                    articleRowMenuEntries(
                        article = article,
                        selectedByOpen = openSelection.selectedByOpen,
                        strings = strings,
                        onSetRead = onSetRead,
                        onSetStarred = onSetStarred,
                        onCopyUrl = onCopyUrl,
                        onOpenInBrowser = onOpenInBrowser,
                        onShare = onShare,
                    )
                },
                // Reset on every open, so it only ever describes this right-click.
                onOpen = {
                    openSelection.selectedByOpen = articleRowContextMenuOpen(selected, select = onClick, activate = onActivate)
                },
            )
            .listRowSurface(
                selectionBackground(selected, focused),
                ListRowKind.ListItem,
                interactionSource,
                decoration = listRowOutline(ListRowKind.ListItem, selected, focused),
            )
            .heightIn(min = listRowMinHeight())
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
