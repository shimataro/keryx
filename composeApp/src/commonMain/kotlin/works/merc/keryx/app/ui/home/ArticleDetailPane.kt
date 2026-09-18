package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewStateWithHTMLData
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleReaderRow
import works.merc.keryx.app.domain.readerBody
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.toReaderRow
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.isTouchPrimary
import works.merc.keryx.app.platform.platformShowsOwnCopyConfirmation
import works.merc.keryx.app.platform.setNativeWebViewImportantForAccessibility
import works.merc.keryx.app.platform.setNativeWebViewScrollbarColor
import works.merc.keryx.app.platform.setNativeWebViewVisible
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_content
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_star
import works.merc.keryx.app.resources.article_unstar
import works.merc.keryx.app.resources.article_url_copied
import works.merc.keryx.app.resources.common_back
import works.merc.keryx.app.resources.home_no_article_selected
import works.merc.keryx.app.ui.article.ArticleHtmlTheme
import works.merc.keryx.app.ui.article.articleNoContentHtml
import works.merc.keryx.app.ui.article.articlePlaceholderHtml
import works.merc.keryx.app.ui.article.extractLinks
import works.merc.keryx.app.ui.article.wrapArticleHtml
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
 * Displays the selected article and provides actions for starring, marking it unread, copying its URL, and opening it in a browser.
 *
 * @param vm The view model supplying the selected article and handling article actions.
 * @param onActivated Invoked when the pane is activated.
 * @param copyPulse A counter that signals a keyboard copy action for the selected article.
 */
@Composable
fun ArticleDetailPane(
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
    onActivated: () -> Unit = {},
    copyPulse: Int = 0,
    onNavigateUp: (() -> Unit)? = null,
    swipeNavigation: ArticleSwipeNavigation? = null,
    // Overridable only so a desktopTest can exercise the touch-primary branch below without a real
    // touch-primary platform to run on; every real call site relies on the default.
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
) {
    val article by vm.selectedArticle.collectAsState()

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
        remember(pages, contents, article) {
            ArticleReaderPaging(
                pages = readerPages(pages, article),
                contents = readerContents(contents, article),
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
        article = article,
        modifier = modifier,
        onActivated = onActivated,
        copyPulse = copyPulse,
        onToggleStar = { vm.toggleStarSelected() },
        onMarkUnread = { vm.markSelectedUnread() },
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
 * whether [article] is selected. Compose Desktop's `SwingInteropContainer` runs a
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
    article: Articles?,
    modifier: Modifier = Modifier,
    onActivated: () -> Unit = {},
    copyPulse: Int = 0,
    onToggleStar: () -> Unit = {},
    onMarkUnread: () -> Unit = {},
    onNavigateUp: (() -> Unit)? = null,
    swipeNavigation: ArticleSwipeNavigation? = null,
    readerPaging: ArticleReaderPaging? = null,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit =
        { html, body, articleUrl, active -> ArticleWebView(html, body, articleUrl, active) },
) {
    // Inline "copied" feedback for the toolbar copy button. Kept above any conditional so this
    // composable never leaves/re-enters composition — otherwise LaunchedEffect(copyPulse) would
    // re-fire with a stale pulse value and flash ✓ without a copy.
    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(COPIED_FEEDBACK_MS)
            showCopied = false
        }
    }
    // Android also reports the copy via a Snackbar (desktop has no in-app snackbar convention —
    // see LocalSnackbarHostState's own KDoc, so this is a no-op there) — except on API 33+, where
    // the system already shows its own clipboard-copy confirmation and this would just duplicate
    // it (see platformShowsOwnCopyConfirmation's own KDoc). A second, independent effect so
    // showSnackbar's own (much longer) suspend-until-dismissed duration never delays the ✓ icon
    // reset above.
    val snackbarHostState = LocalSnackbarHostState.current
    val copiedMessage = stringResource(Res.string.article_url_copied)
    LaunchedEffect(showCopied) {
        if (showCopied && !platformShowsOwnCopyConfirmation) snackbarHostState?.showSnackbar(copiedMessage)
    }
    // Keyboard ⌘/Ctrl+Shift+C copies the selected article (shown in this pane), so mirror the
    // button's feedback here. Initial copyPulse == 0 is skipped; only increments from HomeScreen
    // fire it.
    LaunchedEffect(copyPulse) { if (copyPulse != 0) showCopied = true }

    val placeholderText = stringResource(Res.string.home_no_article_selected)
    val noContentText = stringResource(Res.string.article_no_content)
    val noTitleText = stringResource(Res.string.article_no_title)

    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fontScale = LocalDensity.current.fontScale
    val theme = remember(surface, onSurface, linkColor, mutedColor, fontScale) {
        ArticleHtmlTheme(surface, onSurface, linkColor, mutedColor, fontScale)
    }

    val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)

    // Only enabled where the caller supplied sibling-article navigation (swipeNavigation != null
    // — see ArticleSwipeNavigation's own KDoc for why this, not onNavigateUp, is the swipe
    // boundary) and only while an article is actually on screen to swipe away from. At
    // PaneLayout.Triple the reader is a permanent, keyboard-driven pane shared with desktop,
    // exactly like J/K there — a swipe gesture has no place in that state, and no caller passes
    // swipeNavigation there.
    val swipeEnabled = isTouchPrimary && swipeNavigation != null && article != null
    val selectedId = article?.id
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
                article = article,
                showCopied = showCopied,
                onToggleStar = onToggleStar,
                onMarkUnread = onMarkUnread,
                onCopied = { showCopied = true },
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
                        article?.title?.takeIf { it.isNotBlank() }?.let { contentDescription = it }
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
                if (readerPaging != null && article != null && isTouchPrimary) {
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
                        requestContent = readerPaging.requestContent,
                        widthPx = { swipeController.widthPx },
                        theme = theme,
                        noTitleText = noTitleText,
                        noContentText = noContentText,
                        openInBrowserTooltip = openInBrowserTooltip,
                        reader = reader,
                    )
                } else {
                    val document = remember(theme, article, noTitleText, placeholderText, noContentText, openInBrowserTooltip) {
                        singleReaderDocument(theme, article, noTitleText, placeholderText, noContentText, openInBrowserTooltip)
                    }
                    reader(document.html, document.body, document.articleUrl, true)
                }
            }
        }
    }
}

/**
 * The detail pane's action toolbar. Always renders all four actions — star, mark unread, copy
 * URL, open in browser — rather than hiding them when [article] is `null` or lacks a usable URL,
 * per the "prefer disabled over hidden" rule in `.claude/skills/ui-guidelines/SKILL.md`: with an
 * unconditional toolbar shape, the reader beneath it (see [ArticleDetailPaneContent]) never has
 * to move.
 *
 * [onNavigateUp], when non-null, adds a leading back button (this pane is shown alone or paired
 * at a narrow [PaneLayout] — see `ArticleDetailPane`'s KDoc). The action group stays pinned to the
 * trailing edge via a leading `Spacer(weight(1f))` rather than a fixed end arrangement, so its
 * position doesn't move whether or not the back button is present.
 */
@Composable
private fun ArticleDetailToolbar(
    article: Articles?,
    showCopied: Boolean,
    onToggleStar: () -> Unit,
    onMarkUnread: () -> Unit,
    onCopied: () -> Unit,
    onNavigateUp: (() -> Unit)? = null,
) {
    val hasArticle = article != null
    val starred = article?.is_starred == 1L
    val url = article?.url.orEmpty()
    val copyOpenEnabled = hasArticle && hasUsableUrl(article.url)

    KeryxPaneTopBar(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
            val markUnreadTooltip = stringResource(Res.string.article_mark_as_unread)
            TooltipIconButton(tooltip = markUnreadTooltip, onClick = onMarkUnread, enabled = hasArticle) {
                KeryxIcon(KeryxIcons.Circle, contentDescription = markUnreadTooltip)
            }
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            val copyUrlTooltip = stringResource(
                if (showCopied) Res.string.article_url_copied else Res.string.article_copy_url,
            )
            TooltipIconButton(
                tooltip = copyUrlTooltip,
                enabled = copyOpenEnabled,
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipboardEntries.ofText(url))
                        onCopied()
                    }
                },
            ) {
                KeryxIcon(
                    if (showCopied) KeryxIcons.CheckOutlined else KeryxIcons.ContentCopy,
                    contentDescription = copyUrlTooltip,
                )
            }
            val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)
            TooltipIconButton(tooltip = openInBrowserTooltip, enabled = copyOpenEnabled, onClick = { BrowserOpener.open(url) }) {
                KeryxIcon(KeryxIcons.PublicOutlined, contentDescription = openInBrowserTooltip)
            }
        }
    }
}

/**
 * Formats the article author and publication time as a metadata line.
 *
 * Blank authors and unavailable publication times are omitted.
 *
 * @param author The article's author, if available.
 * @param publishedAt The article's publication time in Unix milliseconds, if available.
 * @return The formatted metadata line, or an empty string when neither value is available.
 */
internal fun articleMetaText(author: String?, publishedAt: Long?): String =
    listOfNotNull(author?.takeIf { it.isNotBlank() }, formatTimestamp(publishedAt).ifBlank { null })
        .joinToString(" · ")

/**
 * One pager page's rendered document, plus the two values [ArticleWebView] needs alongside it.
 *
 * @property html The complete document to render.
 * @property body The raw article body HTML, for resolving which link clicks escape to the browser.
 * @property articleUrl The article's own URL.
 */
private data class ReaderDocument(val html: String, val body: String, val articleUrl: String?)

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
 */
private fun readerDocument(
    theme: ArticleHtmlTheme,
    row: ArticleListRow,
    full: ArticleReaderRow?,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
): ReaderDocument {
    val url = full?.url ?: row.url
    val title = (full?.title ?: row.title).ifBlank { noTitleText }
    val meta = articleMetaText(full?.author, full?.published_at ?: row.published_at)
    val body = full?.readerBody()
    val html = when {
        full == null -> wrapArticleHtml(theme, title, meta, body = "", baseUrl = url, titleUrl = url, titleTooltip = openInBrowserTooltip)
        body.isNullOrBlank() -> articleNoContentHtml(theme, title, meta, noContentText, titleUrl = url, titleTooltip = openInBrowserTooltip)
        else -> wrapArticleHtml(theme, title, meta, body, baseUrl = url, titleUrl = url, titleTooltip = openInBrowserTooltip)
    }
    return ReaderDocument(html, body.orEmpty(), url)
}

/**
 * The document the single, non-pager reader renders — `PaneLayout.Triple`, and the narrow layouts'
 * own "nothing selected" state.
 *
 * Routed through [readerDocument] rather than repeating its `when`: the two used to derive the body
 * (and the content/summary fallback) separately, which is one edit away from the single reader and
 * the pager disagreeing about how to render the same article. The only case this adds is the
 * placeholder, which the pager has no equivalent of.
 */
private fun singleReaderDocument(
    theme: ArticleHtmlTheme,
    article: Articles?,
    noTitleText: String,
    placeholderText: String,
    noContentText: String,
    openInBrowserTooltip: String,
): ReaderDocument {
    if (article == null) return ReaderDocument(articlePlaceholderHtml(theme, placeholderText), body = "", articleUrl = null)
    return readerDocument(theme, article.toListRow(), article.toReaderRow(), noTitleText, noContentText, openInBrowserTooltip)
}

/**
 * How many physical [ArticleWebView] instances the reader keeps alive at a narrow layout — one
 * for the settled page and one for each neighbour [HorizontalPager]'s `beyondViewportPageCount = 1`
 * keeps composed. See [ArticleWebViewCarousel] for why this must match that value.
 */
internal const val ARTICLE_READER_SLOT_COUNT = 3

/**
 * The always-alive `WebView`s the narrow-layout reader actually shows, positioned by hand to track
 * [pagerState]'s own scroll rather than living inside its lazily-composed page slots.
 *
 * The [HorizontalPager] above this (see its call site's own comment) is invisible on purpose: a
 * `LazyLayout` disposes a page's composition — and therefore any `AndroidView`-hosted native
 * `WebView` inside it — the moment that page scrolls past `beyondViewportPageCount`, and recreates
 * it from scratch when the page re-enters range. On Android that recreation briefly shows nothing
 * (the article the user is mid-swipe on going blank for a couple of frames — confirmed on-device
 * with frame-by-frame capture), because it tears down and rebuilds the real `android.webkit.WebView`
 * behind it, not just its Compose content. Forward and backward swipes are asymmetric here for
 * reasons rooted in the pager's own internals, not this app's code, which is why the flicker was
 * only ever seen swiping towards the next article. See "Article Reader" in `docs/app-architecture.md`
 * for the full investigation.
 *
 * This composable sidesteps the whole mechanism: [ARTICLE_READER_SLOT_COUNT] physical slots are
 * composed unconditionally, at fixed call sites, for the life of this composable — the same
 * "compose it once, never behind an `if`" idiom [ArticleDetailPaneContent] already uses for the
 * `PaneLayout.Triple` reader — so their `AndroidView`-hosted `WebView`s are never disposed by an
 * ordinary page turn (see [ArticleWebViewSlot] for the one slot an edge of the list does release).
 * [slotIndex] assigns each slot the page index that shares its residue modulo
 * [ARTICLE_READER_SLOT_COUNT]; since any three consecutive page indices always occupy three
 * distinct residues, the settled page and both its neighbours are guaranteed distinct slots, and
 * stepping to an adjacent page changes at most one slot's assignment — the two pages already
 * resident (including the one just left) keep the exact same `WebView`, just repositioned via
 * [ArticleWebViewSlot]'s own `Modifier.offset`. Reading position across a swipe survives for
 * exactly the same reason it did before ([HorizontalPager]'s own `beyondViewportPageCount = 1`
 * KDoc): a page more than one away from the settled one is no longer any slot's assignment, so its
 * `WebView` gets reused for whatever page *is* newly in range — reloading fresh content — the same
 * "two articles away and back loses position" limit `docs/testing.md` already documents.
 */
@Composable
private fun ArticleWebViewCarousel(
    pagerState: PagerState,
    pages: List<ArticleListRow>,
    contents: Map<String, ArticleReaderRow>,
    selectedId: String?,
    requestContent: (String) -> Unit,
    widthPx: () -> Float,
    theme: ArticleHtmlTheme,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit,
) {
    for (slot in 0 until ARTICLE_READER_SLOT_COUNT) {
        key(slot) {
            ArticleWebViewSlot(
                slot = slot,
                pagerState = pagerState,
                pages = pages,
                contents = contents,
                selectedId = selectedId,
                requestContent = requestContent,
                widthPx = widthPx,
                theme = theme,
                noTitleText = noTitleText,
                noContentText = noContentText,
                openInBrowserTooltip = openInBrowserTooltip,
                reader = reader,
            )
        }
    }
}

/**
 * The page index [slot] should show right now, or `null` when no in-range page has that residue
 * (an edge of the list — there is no page before the first one, or after the last).
 *
 * Only the settled page and its immediate neighbours (the same range
 * `HorizontalPager`'s `beyondViewportPageCount = 1` used to compose) are ever candidates, so this
 * never has to scan the whole list.
 */
internal fun slotIndex(slot: Int, currentPage: Int, pageCount: Int): Int? {
    for (candidate in (currentPage - 1)..(currentPage + 1)) {
        if (candidate !in 0 until pageCount) continue
        val residue = ((candidate % ARTICLE_READER_SLOT_COUNT) + ARTICLE_READER_SLOT_COUNT) % ARTICLE_READER_SLOT_COUNT
        if (residue == slot) return candidate
    }
    return null
}

/**
 * One physical slot of [ArticleWebViewCarousel]: composed unconditionally so its [reader] (and the
 * native `WebView` inside it) survives every ordinary page turn, tracking [pagerState]'s scroll
 * through a lambda-based [Modifier.offset] — read at layout time, not recomposition, the same way
 * the gesture's own rubber band already is (see [ArticleSwipeController.offset]'s call site).
 *
 * A slot with no assignment emits nothing at all, which disposes whatever it last held: Compose
 * removes the child composition — and with it the native `WebView` — when a composable returns
 * before emitting. That only happens at an edge of the list, where fewer than three pages are
 * within reach, and only ever to a slot holding a page two or more away from the settled one; the
 * settled page and both its neighbours always have an assignment, so nothing on screen (or one
 * swipe from it) is ever torn down here. Such a page is already outside the reading position this
 * carousel preserves, so the cost is one `WebView` rebuilt the next time the list edge is left,
 * not a lost reading position.
 */
@Composable
private fun ArticleWebViewSlot(
    slot: Int,
    pagerState: PagerState,
    pages: List<ArticleListRow>,
    contents: Map<String, ArticleReaderRow>,
    selectedId: String?,
    requestContent: (String) -> Unit,
    widthPx: () -> Float,
    theme: ArticleHtmlTheme,
    noTitleText: String,
    noContentText: String,
    openInBrowserTooltip: String,
    reader: @Composable (html: String, body: String, articleUrl: String?, active: Boolean) -> Unit,
) {
    val index = slotIndex(slot, pagerState.currentPage, pages.size) ?: return
    val row = pages.getOrNull(index) ?: return
    LaunchedEffect(row.id) { requestContent(row.id) }
    val full = contents[row.id]
    val document = remember(theme, row, full, noTitleText, noContentText, openInBrowserTooltip) {
        readerDocument(theme, row, full, noTitleText, noContentText, openInBrowserTooltip)
    }
    // A page the user has not swiped to is held ready, not shown. It must not be reachable by a
    // screen reader's linear traversal (its WebView is a real view, clipped rather than
    // semantically hidden), and its own document must not be able to send anyone to an external
    // browser — see ArticleWebView.
    val active = row.id == selectedId
    Box(
        Modifier.fillMaxSize()
            .offset {
                val offsetFraction = index - pagerState.currentPage - pagerState.currentPageOffsetFraction
                IntOffset((offsetFraction * widthPx()).roundToInt(), 0)
            }
            .let { if (active) it else it.clearAndSetSemantics {} },
    ) {
        reader(document.html, document.body, document.articleUrl, active)
    }
}

/**
 * Displays a prebuilt HTML [html] document in a native web view.
 *
 * [html] is the complete, already-themed document to render (built by
 * [works.merc.keryx.app.ui.article.wrapArticleHtml] or one of its sibling builders); this
 * composable only owns the native WebView lifecycle. [body] is the raw article body HTML (not
 * the wrapped document) used to decide which link clicks should escape to the system browser.
 * [active] is whether this is the page actually on screen; an inactive page (one the reader's
 * pager is holding ready either side of it) is refused every navigation it attempts.
 *
 * [articleUrl] is the article's own URL — the same value [html]'s `<base href>` was built from,
 * if any — and does double duty: resolving [body]'s relative `<a href>` values to the same
 * absolute form the WebView itself will navigate to, and (since the rendered title is itself a
 * link to this same URL) being added to the known-outbound-link set alongside those resolved
 * body links.
 */
@Composable
private fun ArticleWebView(html: String, body: String, articleUrl: String?, active: Boolean) {
    // Only genuine outbound links from the article's own HTML are forwarded to the system
    // browser. A plain "any http(s) main-frame request" check would also catch SNS-embed
    // widgets' own internal requests (confirmed during the spike for the X/Twitter widget),
    // breaking the embed instead of letting it render in place.
    val knownLinks = remember { mutableStateOf(emptySet<String>()) }
    // Read inside the interceptor, which is remembered once and therefore cannot capture the
    // parameter directly.
    val isActive = rememberUpdatedState(active)
    LaunchedEffect(body, articleUrl) {
        val links = extractLinks(body, articleUrl.orEmpty())
        knownLinks.value = articleUrl?.takeIf { isHttpOrHttpsUrl(it) }?.let { links + it } ?: links
    }
    val scope = rememberCoroutineScope()
    val interceptor = remember {
        object : RequestInterceptor {
            override fun onInterceptUrlRequest(request: WebRequest, navigator: WebViewNavigator): WebRequestInterceptResult {
                // A page the reader is only holding ready has no business navigating anywhere: the
                // library reports a script-driven `location.href` or a meta refresh through this
                // same callback as a real tap, so without this an article the user has never opened
                // could send them to an attacker-chosen page in their browser, or walk its own
                // off-screen WebView onto an arbitrary remote origin in the shared profile.
                if (!isActive.value) return WebRequestInterceptResult.Reject
                return if (request.url in knownLinks.value) {
                    BrowserOpener.open(request.url)
                    WebRequestInterceptResult.Reject
                } else {
                    WebRequestInterceptResult.Allow
                }
            }
        }
    }
    val navigator = rememberWebViewNavigator(scope, interceptor)
    val webViewState = rememberWebViewStateWithHTMLData(data = html)

    // Without an explicit data directory, WebView2 tries to create its own data folder next to
    // the host executable, which fails with HRESULT(0x80070005) Access Denied when that
    // location isn't user-writable (e.g. java.exe under Program Files during `gradlew run`, or
    // a per-machine install under Program Files) — see docs/known-issues.md. The failed creation
    // throws WebViewException, which extends Exception rather than RuntimeException, so the
    // library's own `catch (e: RuntimeException)` around the native call doesn't catch it; it
    // propagates uncaught and the library's creation retry timer never gets a chance to stop
    // itself, retrying indefinitely. Setting this is harmless on macOS/Linux, which already
    // resolve a writable default, so no OS branch is needed.
    remember(webViewState) {
        webViewState.webSettings.desktopWebSettings.dataDirectory = webViewDataDirectory(AppDirs.cacheDir())
    }

    // Workaround: this (beta) library's rememberWebViewStateWithHTMLData doesn't reliably
    // navigate past its initial "about:blank" on desktop — confirmed during the spike. Push
    // the HTML manually once the WebView reports it's idle. Guarded on the rendered *document*
    // rather than an article id: this one WebView also renders the "no article selected"
    // placeholder and the "no content" notice, which have no article id, and comparing the HTML
    // also picks up a theme/font-scale change for an otherwise-unchanged article.
    val loadedHtml = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(webViewState.loadingState, html) {
        if (shouldLoadArticleHtml(webViewState.loadingState, loadedHtml.value, html)) {
            loadedHtml.value = html
            navigator.loadHtml(html)
        }
    }

    // The native webview (a heavyweight AWT component wrapping a real OS-native browser
    // window, not a Compose-composited texture) briefly paints at its default (0,0) position
    // before Compose's SwingPanel interop applies the real layout bounds — a known Compose
    // Desktop interop limitation (JetBrains YouTrack CMP-5780), not specific to this app. Hide
    // the panel from the moment it's created and only reveal it once this composable's own
    // layout position is known, giving Compose's own bounds sync a frame to catch up first.
    // Revealed once, not on every article switch, since the panel is reused after creation.
    val nativePanel = remember { mutableStateOf<NativeWebView?>(null) }
    val hasPositioned = remember { mutableStateOf(false) }
    val revealed = remember { mutableStateOf(false) }
    LaunchedEffect(hasPositioned.value, nativePanel.value) {
        val panel = nativePanel.value
        if (hasPositioned.value && panel != null && !revealed.value) {
            withFrameNanos {}
            revealed.value = true
            setNativeWebViewVisible(panel, true)
        }
    }
    // A page the reader is only holding ready must not be reachable by a screen reader's linear
    // traversal — see setNativeWebViewImportantForAccessibility's own KDoc for why the surrounding
    // Box's clearAndSetSemantics{} is not enough on its own to guarantee that.
    LaunchedEffect(active, nativePanel.value) {
        nativePanel.value?.let { setNativeWebViewImportantForAccessibility(it, active) }
    }
    // See setNativeWebViewScrollbarColor's own KDoc — a no-op on desktop, where the document's own
    // `color-scheme` already paints the scrollbar; load-bearing on Android, whose root-frame
    // scrollbar the CSS never reaches.
    val scrollbarColor = MaterialTheme.colorScheme.outline
    LaunchedEffect(scrollbarColor, nativePanel.value) {
        nativePanel.value?.let { setNativeWebViewScrollbarColor(it, scrollbarColor) }
    }

    WebView(
        state = webViewState,
        modifier = Modifier.fillMaxSize().onGloballyPositioned {
            if (!hasPositioned.value && it.size.width > 0 && it.size.height > 0) {
                hasPositioned.value = true
            }
        },
        navigator = navigator,
        onCreated = { panel ->
            setNativeWebViewVisible(panel, false)
            setNativeWebViewImportantForAccessibility(panel, active)
            nativePanel.value = panel
        },
    )
}

/**
 * Whether the pending [html] document still needs to be pushed to the WebView, given the last
 * document pushed ([loadedHtml]) and the view's [loadingState]. Compared by document rather than
 * by article id: the placeholder and the "no content" notice share the same WebView, and a
 * theme or font-scale change produces a new document for an otherwise-unchanged article.
 */
internal fun shouldLoadArticleHtml(loadingState: LoadingState, loadedHtml: String?, html: String): Boolean =
    loadingState is LoadingState.Finished && loadedHtml != html

/**
 * The directory the reader's native WebView should store its browsing data (cookies, local
 * storage, WebView2's own profile) in, given the app's [cacheDir]. Trims any trailing path
 * separator from [cacheDir] so the joined path never doubles up a slash regardless of whether
 * the caller's directory string already ends with one.
 */
internal fun webViewDataDirectory(cacheDir: String): String =
    cacheDir.trimEnd('/', '\\') + "/webview"
