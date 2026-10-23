package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.github.kdroidfilter.webview.web.WebView
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.domain.readerBody
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.toReaderRow
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.isTouchPrimary
import works.merc.keryx.app.presentation.articleMetaText
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.presentation.home.ReaderContent
import works.merc.keryx.app.presentation.home.canOpenInBrowser
import works.merc.keryx.app.presentation.home.canShareArticleUrl
import works.merc.keryx.app.presentation.home.hasUsableUrl
import works.merc.keryx.app.presentation.home.listRow
import works.merc.keryx.app.presentation.home.readerContents
import works.merc.keryx.app.presentation.home.readerPages
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_read
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_content
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_share
import works.merc.keryx.app.resources.article_star
import works.merc.keryx.app.resources.article_state_read
import works.merc.keryx.app.resources.article_state_unread
import works.merc.keryx.app.resources.article_unstar
import works.merc.keryx.app.resources.article_url_copied
import works.merc.keryx.app.resources.common_back
import works.merc.keryx.app.resources.home_no_article_selected
import works.merc.keryx.app.presentation.article.ArticleHtmlTheme
import works.merc.keryx.app.presentation.article.articleNoContentHtml
import works.merc.keryx.app.presentation.article.articlePlaceholderHtml
import works.merc.keryx.app.presentation.article.wrapArticleHtml
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxPaneTopBar
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/** How long the copy button shows its inline ✓ / "copied" state before reverting. */
private const val COPIED_FEEDBACK_MS = 1500L

/** Semantics test tag on the article reader's container, used to assert its bounds stay fixed across selection changes. */
internal const val ARTICLE_READER_TEST_TAG = "article-reader"

/**
 * Displays the selected article and provides actions for starring it, toggling its read state, opening it in a browser, and copying its URL.
 *
 * @param vm The view model supplying the selected article and handling article actions.
 * @param onActivated Invoked when the pane is activated.
 * @param copyPulse A counter bumped whenever the displayed article's URL is copied, by any route
 *   (this pane's own copy button included — see [ArticleUrlCopier]); each increment flashes the copy
 *   button's inline ✓. Only increments made while this pane is composed count.
 * @param onCopyUrl The shared "copy article URL" handler ([ArticleUrlCopier.copy]) the toolbar's copy
 *   button calls with the selected article's row; the pane never writes the clipboard itself.
 * @param onOpenInBrowser The shared "open in browser" handler ([openInBrowserIfAllowed]) the
 *   toolbar's open button calls with the displayed article. The button is enabled only for an
 *   http(s) URL ([canOpenInBrowser]); copying needs only a non-blank one.
 * @param onShare The shared "Share" handler ([ArticleSharer.share]) the toolbar's share button calls
 *   with the displayed article, enabled by the same [canShareArticleUrl] rule as every other share
 *   route. `null` where the platform has no share sheet, which leaves the button out.
 */
@Composable
fun ArticleDetailPane(
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
    onActivated: () -> Unit = {},
    copyPulse: Int = 0,
    onCopyUrl: (ArticleListRow) -> Unit = {},
    onOpenInBrowser: (ArticleListRow) -> Unit = { openInBrowserIfAllowed(it.url) },
    onShare: ((ArticleListRow) -> Unit)? = null,
    onNavigateUp: (() -> Unit)? = null,
    swipeNavigation: ArticleSwipeNavigation? = null,
    // Overridable only so a desktopTest can exercise the touch-primary branch below without a real
    // touch-primary platform to run on; every real call site relies on the default.
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    // The reader's document follows readerContent; the toolbar and the pager follow the selection
    // itself (selectedArticleRow), which is current the moment an article is selected, before its
    // body has loaded — so neither acts on, or scrolls back to, the previous article meanwhile.
    val readerContent by vm.readerContent.collectAsState()
    val selectedRow by vm.selectedArticleRow.collectAsState()
    val shownUnread by vm.selectedArticleShownUnread.collectAsState()
    val feedName by vm.selectedFeedName.collectAsState()
    val feedFaviconUrl by vm.selectedFeedFaviconUrl.collectAsState()
    // Only a touch-primary platform names the feed in each article's byline (see
    // ArticleDetailPaneContent), so only there is the feed lookup collected at all. The structural
    // variant skips the per-fetch etag/last_modified rewrites, which change nothing a byline shows.
    val feedsById = if (isTouchPrimary) {
        val feeds by vm.structuralFeeds.collectAsState()
        remember(feeds) { feeds.associateBy { it.id } }
    } else {
        emptyMap()
    }

    // Built only where a swipe exists at all *and* the platform is touch-driven, which is what
    // keeps the pager off desktop structurally rather than by way of a window-width constant: a
    // transient pre-layout frame can resolve PaneLayout.Single there (see HomeScreen's own comment
    // on BoxWithConstraints), and mounting even one frame of heavyweight AWT WebViews is exactly
    // what this pane must never do. Collecting inside the branch also keeps a desktop composition
    // from subscribing to pagerArticles at all — it is the one `WhileSubscribed` flow on the
    // ViewModel for that reason.
    val readerPaging = if (swipeNavigation != null && isTouchPrimary) {
        val pages by vm.pagerArticles.collectAsState()
        val contents by vm.articleContents.collectAsState()
        // Remembered so the pane below can skip recomposition: ArticleReaderPaging holds a List and
        // a Map and is therefore unstable, so a fresh instance every time would make every article
        // write recompose the whole reader.
        val loaded = (readerContent as? ReaderContent.Full)?.article
        remember(pages, contents, selectedRow, loaded) {
            ArticleReaderPaging(
                pages = readerPages(pages, selectedRow),
                contents = readerContents(contents, loaded),
                requestContent = vm::requestArticleContent,
                onPageSettled = vm::selectArticle,
            )
        }
    } else {
        null
    }
    // Nothing is holding these bodies open once the reader leaves the composition.
    DisposableEffect(vm) { onDispose { vm.clearArticleContents() } }

    ArticleDetailPaneContent(
        readerContent = readerContent,
        selectedRow = selectedRow,
        feedName = feedName,
        feedFaviconUrl = feedFaviconUrl,
        feedsById = feedsById,
        modifier = modifier,
        shownUnread = shownUnread,
        onActivated = onActivated,
        copyPulse = copyPulse,
        onCopyUrl = onCopyUrl,
        onOpenInBrowser = onOpenInBrowser,
        onShare = onShare,
        onToggleStar = { vm.toggleStarSelected() },
        onToggleRead = { vm.toggleReadSelected() },
        onNavigateUp = onNavigateUp,
        swipeNavigation = swipeNavigation,
        readerPaging = readerPaging,
        isTouchPrimary = isTouchPrimary,
    )
}

/**
 * The detail pane's Compose shell: a fixed-position toolbar and, under it, the article reader.
 *
 * The reader is a **heavyweight native AWT surface** (an OS browser view hosted through
 * `SwingPanel`), so it is composed unconditionally here — never behind an `if` — regardless of
 * whether an article is selected. Compose Desktop's `SwingInteropContainer` runs a
 * `validate()` + `repaint()` on the *whole window* whenever that surface is added, removed, or
 * moved, so unmounting it for the "no article selected" state (as this pane used to do) flickers
 * every pane, not just this one. See `docs/known-issues.md` for the full investigation.
 *
 * The corollary is that nothing Compose draws can appear over the reader's content area (the same
 * heavyweight/lightweight interop limitation that makes the app's dialogs real `DialogWindow`s —
 * see `ui/common/KeryxDialogs.kt`). The "no article selected" and "no content" messages are
 * therefore rendered as HTML inside the reader itself, not as `Text` here.
 *
 * [reader] defaults to the real [ArticleWebView] and exists as a parameter purely so tests can
 * substitute a lightweight stub: the real reader is a genuine native OS browser view, which a
 * Compose UI test's offscreen renderer cannot host.
 */
@Composable
internal fun ArticleDetailPaneContent(
    /** What the single reader shows — see `HomeViewModel.readerContent`. */
    readerContent: ReaderContent,
    /**
     * The selected article's row, which the toolbar, the pager and the accessibility label follow —
     * see `HomeViewModel.selectedArticleRow`. Ahead of [readerContent] while a body is loading.
     */
    selectedRow: ArticleListRow? = readerContent.listRow(),
    feedName: String? = null,
    feedFaviconUrl: String? = null,
    feedsById: Map<String, Feeds> = emptyMap(),
    modifier: Modifier = Modifier,
    /** Whether the read/unread button shows the unread state — see `HomeViewModel.selectedArticleShownUnread`. */
    shownUnread: Boolean = selectedRow?.is_read == 0L,
    onActivated: () -> Unit = {},
    copyPulse: Int = 0,
    onCopyUrl: (ArticleListRow) -> Unit = {},
    onOpenInBrowser: (ArticleListRow) -> Unit = { openInBrowserIfAllowed(it.url) },
    onShare: ((ArticleListRow) -> Unit)? = null,
    onToggleStar: () -> Unit = {},
    onToggleRead: () -> Unit = {},
    onNavigateUp: (() -> Unit)? = null,
    swipeNavigation: ArticleSwipeNavigation? = null,
    readerPaging: ArticleReaderPaging? = null,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit =
        { html, body, articleUrl, active -> ArticleWebView(html, body, articleUrl, active) },
) {
    // Inline "copied" feedback for the toolbar copy button.
    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(COPIED_FEEDBACK_MS)
            showCopied = false
        }
    }
    // A copy of the article shown in this pane, made through any route — this pane's own button,
    // keyboard, menu bar, or the article row's context menu — all go through the shared
    // ArticleUrlCopier, which bumps copyPulse (and shows Android's snackbar itself). Only pulses
    // raised after this pane entered composition count: HomeScreen composes the pane in a different
    // branch per layout (and not at all while a phone-width screen shows the article list), so it
    // can re-enter with a copyPulse bumped long ago, which must not flash ✓ without a copy.
    val pulseAtEntry = remember { copyPulse }
    LaunchedEffect(copyPulse) { if (copyPulse != pulseAtEntry) showCopied = true }

    val placeholderText = stringResource(Res.string.home_no_article_selected)
    val noContentText = stringResource(Res.string.article_no_content)
    val noTitleText = stringResource(Res.string.article_no_title)

    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = LocalDensity.current.fontScale
    val theme = remember(surface, onSurface, linkColor, mutedColor, fontScale) {
        ArticleHtmlTheme(surface.toArgb(), onSurface.toArgb(), linkColor.toArgb(), mutedColor.toArgb(), fontScale)
    }

    val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)

    // Where the feed is named: in the toolbar on desktop, and in each article's own byline on a
    // touch-primary platform (Android), matching the SwiftUI app's macOS/iOS split. A phone-width
    // toolbar has no room for the name beside the back button and four actions, and a byline lets
    // each swiped-to page name its own feed rather than the toolbar catching up once a swipe settles.
    // Keyed on the platform, not the width, so the name never moves between layouts on one device.
    val feedInByline = isTouchPrimary
    val bylineFeeds = if (feedInByline) feedsById else emptyMap()

    // Only enabled where the caller supplied sibling-article navigation (swipeNavigation != null
    // — see ArticleSwipeNavigation's own KDoc for why this, not onNavigateUp, is the swipe
    // boundary) and only while an article is actually on screen to swipe away from. At
    // PaneLayout.Triple the reader is a permanent, keyboard-driven pane shared with desktop,
    // exactly like J/K there — a swipe gesture has no place in that state, and no caller passes
    // swipeNavigation there.
    val swipeEnabled = isTouchPrimary && swipeNavigation != null && selectedRow != null
    val selectedId = selectedRow?.id
    val pages = readerPaging?.pages.orEmpty()
    // Created unconditionally, with no pages at PaneLayout.Triple, so the controller below always
    // has something to drive and this pane keeps one composition shape on every platform. A pager
    // with no pages is inert; the one that renders is composed only where there is a selection.
    // The initial page is resolved once — the scan is over every article in the list, and from
    // here on ArticleReaderPagerSync is what follows the selection.
    val pagerState = rememberPagerState(
        initialPage = remember { pages.indexOfFirst { it.id == selectedId }.coerceAtLeast(0) },
    ) { pages.size }
    val swipeController = rememberArticleSwipeController(
        pagerState = pagerState,
        canSelectNext = swipeNavigation?.canSelectNext ?: { false },
        canSelectPrevious = swipeNavigation?.canSelectPrevious ?: { false },
    )
    ArticleReaderPagerSync(
        pagerState = pagerState,
        pages = pages,
        selectedId = selectedId,
        controller = swipeController,
        onPageSettled = readerPaging?.onPageSettled,
    )

    Column(
        modifier
            .background(surface)
            .fillMaxSize()
            .paneActivation(onActivated),
    ) {
        WindowDragArea(Modifier.fillMaxWidth()) {
            ArticleDetailToolbar(
                article = selectedRow,
                feedName = if (feedInByline) null else feedName,
                feedFaviconUrl = feedFaviconUrl,
                showCopied = showCopied,
                onToggleStar = onToggleStar,
                unread = shownUnread,
                onToggleRead = onToggleRead,
                onCopyUrl = { selectedRow?.let(onCopyUrl) },
                onOpenInBrowser = { selectedRow?.let(onOpenInBrowser) },
                onShare = onShare?.let { share -> { selectedRow?.let(share) } },
                onNavigateUp = onNavigateUp,
            )
        }
        // Two nested boxes: the outer one is the fixed hit area for the swipe gesture (its bounds
        // never move, so the drag's hit-testing and the off-pane "wait for the commit to land"
        // phase both stay well-defined) plus the clip that keeps a sliding reader from spilling
        // into a neighboring pane at PaneLayout.Dual; the inner one carries the actual horizontal
        // offset and is what ARTICLE_READER_TEST_TAG anchors to, so the existing
        // readerBoundsAreIdenticalWithAndWithoutASelection test still measures the reader's own
        // layout bounds (always fillMaxSize, offset is a draw-time translation) rather than the
        // outer container. When swipeEnabled is false, offset never leaves 0 — same measured
        // bounds as before this feature existed. The accessibility actions live on this inner box
        // too, not the outer one: a screen reader focuses the reader's own content node, and that
        // is also the node ARTICLE_READER_TEST_TAG's tests already query with useUnmergedTree.
        Box(
            Modifier.fillMaxSize()
                // Bottom inset clears the navigation bar on Android's edge-to-edge layout (see
                // HomeScreen's Scaffold); zero on desktop (WindowInsets.safeDrawing), so it
                // doesn't change readerBoundsAreIdenticalWithAndWithoutASelection there — both
                // measurements below still shrink by the same (zero) amount.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .clipToBounds()
                .onSizeChanged { swipeController.widthPx = it.width.toFloat() }
                .let { if (swipeEnabled) it.articleSwipeNavigation(swipeController) else it },
        ) {
            val offsetPx = swipeController.offset.value
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .testTag(ARTICLE_READER_TEST_TAG)
                    // The pager's own page-change announcement goes away with
                    // userScrollEnabled = false, and the article body lives in the WebView's own
                    // subtree, so without this a screen-reader user invoking the next/previous
                    // action below would get no confirmation that anything happened.
                    .semantics {
                        selectedRow?.title?.takeIf { it.isNotBlank() }?.let { contentDescription = it }
                        liveRegion = LiveRegionMode.Polite
                    }
                    .articleSwipeAccessibilityActions(
                        enabled = swipeEnabled,
                        canNext = swipeEnabled && swipeNavigation?.canSelectNext?.invoke() == true,
                        canPrevious = swipeEnabled && swipeNavigation?.canSelectPrevious?.invoke() == true,
                        onNext = swipeNavigation?.onSelectNext ?: {},
                        onPrevious = swipeNavigation?.onSelectPrevious ?: {},
                    ),
            ) {
                // isTouchPrimary is checked again here, not just by the caller building
                // readerPaging conditionally: this is the composable that actually decides
                // whether the heavyweight pager mounts, so the invariant belongs at this level
                // too, the same way swipeEnabled above already gates on it.
                if (readerPaging != null && selectedRow != null && isTouchPrimary) {
                    // Invisible: this Pager exists only to drive scroll physics, snapping, and
                    // settle detection (pagerState.currentPage / currentPageOffsetFraction /
                    // settledPage) — see ArticleWebViewCarousel below for why the real content
                    // (and every WebView) lives outside its lazily-composed item slots.
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // Page identity is the article, not the slot it happens to occupy — a sync
                        // merge or an "unread only" toggle reshuffles the list under the pager.
                        key = { index -> pages.getOrNull(index)?.id ?: index },
                        beyondViewportPageCount = 1,
                        // The pager's own touch handling would fight the WebView, which is an
                        // interop view and does not take part in Compose's nested scroll. The
                        // Initial-pass gate in articleSwipeNavigation drives pagerState instead.
                        // It also disables the pager's own accessibility scroll actions, which
                        // articleSwipeAccessibilityActions below already replaces.
                        userScrollEnabled = false,
                    ) { Box(Modifier.fillMaxSize()) }

                    ArticleWebViewCarousel(
                        pagerState = pagerState,
                        pages = pages,
                        contents = readerPaging.contents,
                        selectedId = selectedId,
                        bylineFeeds = bylineFeeds,
                        requestContent = readerPaging.requestContent,
                        widthPx = { swipeController.widthPx },
                        theme = theme,
                        noTitleText = noTitleText,
                        noContentText = noContentText,
                        openInBrowserTooltip = openInBrowserTooltip,
                        reader = reader,
                    )
                } else {
                    val bylineFeed = readerContent.listRow()?.let { bylineFeeds[it.feed_id] }
                    val bylineFeedName = bylineFeed?.displayTitle()
                    val bylineFaviconUrl = bylineFeed?.favicon_url
                    val document = remember(
                        theme, readerContent, bylineFeedName, bylineFaviconUrl, noTitleText, placeholderText, noContentText, openInBrowserTooltip,
                    ) {
                        singleReaderDocument(
                            theme, readerContent, bylineFeedName, bylineFaviconUrl, noTitleText, placeholderText, noContentText, openInBrowserTooltip,
                        )
                    }
                    reader(document.html, document.body, document.articleUrl, true)
                }
            }
        }
    }
}

/**
 * The detail pane's action toolbar. Always renders all its actions — star, mark read/unread, open in
 * browser, copy URL, and (where the platform has a share sheet, i.e. [onShare] is non-null — a
 * per-platform constant) share — rather than hiding them when [article] is `null` or lacks a usable URL,
 * per the "prefer disabled over hidden" rule in `.claude/skills/ui-guidelines/SKILL.md`: with an
 * unconditional toolbar shape, the reader beneath it (see [ArticleDetailPaneContent]) never has
 * to move.
 *
 * [onNavigateUp], when non-null, adds a leading back button (this pane is shown alone or paired
 * at a narrow [PaneLayout] — see `ArticleDetailPane`'s KDoc). The action group stays pinned to the
 * trailing edge via a leading `Spacer(weight(1f))` rather than a fixed end arrangement, so its
 * position doesn't move whether or not the back button is present.
 *
 * [feedName] fills the title slot on desktop only; a touch-primary platform passes `null` and
 * names the feed in the article's own byline instead (see `feedInByline` in
 * [ArticleDetailPaneContent]).
 */
@Composable
private fun ArticleDetailToolbar(
    article: ArticleListRow?,
    feedName: String?,
    feedFaviconUrl: String?,
    showCopied: Boolean,
    onToggleStar: () -> Unit,
    unread: Boolean,
    onToggleRead: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: (() -> Unit)?,
    onNavigateUp: (() -> Unit)? = null,
) {
    val hasArticle = article != null
    val starred = article?.is_starred == 1L
    // Separate rules, shared with every other route: any non-blank URL can be copied or shared, but
    // only an http(s) one is opened.
    val copyEnabled = hasArticle && hasUsableUrl(article.url)
    val openEnabled = hasArticle && canOpenInBrowser(article.url)
    val shareEnabled = hasArticle && canShareArticleUrl(article.url)

    val titleContent: (@Composable () -> Unit)? = if (feedName != null) {
        {
            Row(
                modifier = Modifier.padding(start = if (onNavigateUp != null) 4.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedAvatar(title = feedName, faviconUrl = feedFaviconUrl)
                Spacer(Modifier.width(6.dp))
                Text(
                    feedName,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else null

    KeryxPaneTopBar(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        titleContent = titleContent,
        navigationIcon = if (onNavigateUp == null) {
            null
        } else {
            val backLabel = stringResource(Res.string.common_back)
            val icon: @Composable () -> Unit = {
                TooltipIconButton(tooltip = backLabel, onClick = onNavigateUp) {
                    KeryxIcon(KeryxIcons.ArrowBack, contentDescription = backLabel)
                }
            }
            icon
        },
    ) {
        ToolbarIconGroup {
            val starTooltip = stringResource(if (starred) Res.string.article_unstar else Res.string.article_star)
            TooltipIconButton(tooltip = starTooltip, onClick = onToggleStar, enabled = hasArticle) {
                KeryxIcon(
                    if (starred) KeryxIcons.Star else KeryxIcons.StarBorder,
                    contentDescription = starTooltip,
                    tint = if (starred) StarredColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Shows the state it would change: a filled primary dot while the article is unread (the same
            // dot the article list draws), an outline once read. The label is the action a tap performs.
            val readToggleTooltip = stringResource(
                if (unread) Res.string.article_mark_as_read else Res.string.article_mark_as_unread,
            )
            // The label names the action, so the state itself is spoken separately for a screen reader.
            val readStateText = stringResource(if (unread) Res.string.article_state_unread else Res.string.article_state_read)
            TooltipIconButton(
                tooltip = readToggleTooltip,
                onClick = onToggleRead,
                modifier = Modifier.semantics { stateDescription = readStateText },
                enabled = hasArticle,
            ) {
                KeryxIcon(
                    if (unread) KeryxIcons.CircleFilled else KeryxIcons.Circle,
                    contentDescription = readToggleTooltip,
                    tint = if (unread) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)
            TooltipIconButton(tooltip = openInBrowserTooltip, enabled = openEnabled, onClick = onOpenInBrowser) {
                KeryxIcon(KeryxIcons.PublicOutlined, contentDescription = openInBrowserTooltip)
            }
            val copyUrlTooltip = stringResource(
                if (showCopied) Res.string.article_url_copied else Res.string.article_copy_url,
            )
            TooltipIconButton(
                tooltip = copyUrlTooltip,
                enabled = copyEnabled,
                onClick = onCopyUrl,
            ) {
                KeryxIcon(
                    if (showCopied) KeryxIcons.CheckOutlined else KeryxIcons.ContentCopy,
                    contentDescription = copyUrlTooltip,
                )
            }
            if (onShare != null) {
                val shareTooltip = stringResource(Res.string.article_share)
                TooltipIconButton(tooltip = shareTooltip, enabled = shareEnabled, onClick = onShare) {
                    KeryxIcon(KeryxIcons.Share, contentDescription = shareTooltip)
                }
            }
        }
    }
}

/**
 * One pager page's rendered document, plus the two values [ArticleWebView] needs alongside it.
 *
 * @property html The complete document to render.
 * @property body The raw article body HTML, for resolving which link clicks escape to the browser.
 * @property articleUrl The article's own URL.
 */
internal data class ReaderDocument(val html: String, val body: String, val articleUrl: String?)

/**
 * Builds the document for one pager page from whatever is known about that article so far.
 *
 * A page composes as soon as it comes within the pager's reach, which is before its body has been
 * read from the DB — [full] is `null` until then. That state renders the header alone rather than
 * the "no content" notice: the body is on its way, and saying it does not exist would be wrong for
 * the fraction of a second before it lands. The notice is kept for the case it actually describes,
 * an article that genuinely has neither content nor summary.
 *
 * @param row The list row, which is all that is known about a page until its body loads.
 * @param full The hydrated article, or `null` while its lookup is still in flight.
 * @param feedName The owning feed's name to lead the byline with, or `null` where the toolbar
 *   already shows it (desktop — see `feedInByline` in [ArticleDetailPaneContent]).
 * @param feedFaviconUrl The owning feed's favicon, shown before the byline alongside [feedName].
 */
internal fun readerDocument(
    theme: ArticleHtmlTheme,
    row: ArticleListRow,
    full: ArticleReaderRow?,
    feedName: String?,
    feedFaviconUrl: String?,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
): ReaderDocument {
    val url = full?.url ?: row.url
    val title = (full?.title ?: row.title).ifBlank { noTitleText }
    val meta = articleMetaText(full?.author, full?.published_at ?: row.published_at, feedName)
    val metaIconUrl = feedFaviconUrl.takeIf { feedName != null }
    val body = full?.readerBody()
    val html = when {
        full == null -> wrapArticleHtml(theme, title, meta, body = "", baseUrl = url, titleUrl = url, titleTooltip = openInBrowserTooltip, metaIconUrl = metaIconUrl)
        body.isNullOrBlank() -> articleNoContentHtml(theme, title, meta, noContentText, titleUrl = url, titleTooltip = openInBrowserTooltip, metaIconUrl = metaIconUrl)
        else -> wrapArticleHtml(theme, title, meta, body, baseUrl = url, titleUrl = url, titleTooltip = openInBrowserTooltip, metaIconUrl = metaIconUrl)
    }
    return ReaderDocument(html, body.orEmpty(), url)
}

/**
 * The document the single, non-pager reader renders — `PaneLayout.Triple`, and the narrow layouts'
 * own "nothing selected" state.
 *
 * Routed through [readerDocument] rather than repeating its `when`: the two used to derive the body
 * (and the content/summary fallback) separately, which is one edit away from the single reader and
 * the pager disagreeing about how to render the same article. A [ReaderContent.Header] — a body still
 * loading past its grace period — is exactly the pager's own not-yet-loaded page. The only case this
 * adds is the placeholder, which the pager has no equivalent of.
 */
private fun singleReaderDocument(
    theme: ArticleHtmlTheme,
    content: ReaderContent,
    feedName: String?,
    feedFaviconUrl: String?,
    noTitleText: String,
    placeholderText: String,
    noContentText: String,
    openInBrowserTooltip: String,
): ReaderDocument = when (content) {
    ReaderContent.None -> ReaderDocument(articlePlaceholderHtml(theme, placeholderText), body = "", articleUrl = null)
    is ReaderContent.Header -> readerDocument(
        theme, content.row, full = null, feedName, feedFaviconUrl, noTitleText, noContentText, openInBrowserTooltip,
    )
    is ReaderContent.Full -> readerDocument(
        theme, content.article.toListRow(), content.article.toReaderRow(), feedName, feedFaviconUrl, noTitleText, noContentText, openInBrowserTooltip,
    )
}
