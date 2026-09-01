package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
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
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.isTouchPrimary
import works.merc.keryx.app.platform.platformShowsOwnCopyConfirmation
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
) {
    val article by vm.selectedArticle.collectAsStateSafe(null)

    ArticleDetailPaneContent(
        article = article,
        modifier = modifier,
        onActivated = onActivated,
        copyPulse = copyPulse,
        onToggleStar = { vm.toggleStarSelected() },
        onMarkUnread = { vm.markSelectedUnread() },
        onNavigateUp = onNavigateUp,
        onSelectNext = { vm.selectNext() },
        onSelectPrevious = { vm.selectPrevious() },
        canSelectNext = { vm.canSelectNext() },
        canSelectPrevious = { vm.canSelectPrevious() },
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
    onSelectNext: () -> Unit = {},
    onSelectPrevious: () -> Unit = {},
    canSelectNext: () -> Boolean = { false },
    canSelectPrevious: () -> Boolean = { false },
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
    reader: @Composable (html: String, body: String, baseUrl: String?, articleUrl: String?) -> Unit =
        { html, body, baseUrl, articleUrl -> ArticleWebView(html, body, baseUrl, articleUrl) },
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

    val body = article?.let {
        it.content?.takeIf { content -> content.isNotBlank() } ?: it.summary
    }
    val title = article?.title?.ifBlank { noTitleText }.orEmpty()
    val meta = remember(article?.author, article?.published_at) {
        article?.let { articleMetaText(it.author, it.published_at) }.orEmpty()
    }

    val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)
    val html = remember(theme, article?.id, article?.url, title, meta, body, placeholderText, noContentText, openInBrowserTooltip) {
        val articleUrl = article?.url
        when {
            article == null -> articlePlaceholderHtml(theme, placeholderText)
            body.isNullOrBlank() -> articleNoContentHtml(theme, title, meta, noContentText, titleUrl = articleUrl, titleTooltip = openInBrowserTooltip)
            else -> wrapArticleHtml(theme, title, meta, body, baseUrl = articleUrl, titleUrl = articleUrl, titleTooltip = openInBrowserTooltip)
        }
    }

    // Only enabled where the reader is a "drilled-into" destination with somewhere to navigate
    // back from (onNavigateUp != null — the same narrow-layout signal every other touch-only
    // affordance in this codebase keys off, see the ui-guidelines skill's "Adaptive pane layout &
    // touch affordances") and only while an article is actually on screen to swipe away from.
    // At PaneLayout.Triple (onNavigateUp == null) the reader is a permanent, keyboard-driven pane
    // shared with desktop, exactly like J/K there — a swipe gesture has no place in that state.
    val swipeEnabled = isTouchPrimary && onNavigateUp != null && article != null
    val currentArticleId by rememberUpdatedState(article?.id)
    val swipeController = rememberArticleSwipeController(
        canSelectNext = canSelectNext,
        canSelectPrevious = canSelectPrevious,
        onSelectNext = onSelectNext,
        onSelectPrevious = onSelectPrevious,
        currentArticleId = { currentArticleId },
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
                .clipToBounds()
                .onSizeChanged { swipeController.widthPx = it.width.toFloat() }
                .let { if (swipeEnabled) it.articleSwipeNavigation(swipeController) else it },
        ) {
            val offsetPx = swipeController.offset.value
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .testTag(ARTICLE_READER_TEST_TAG)
                    .articleSwipeAccessibilityActions(
                        enabled = swipeEnabled,
                        canNext = swipeEnabled && canSelectNext(),
                        canPrevious = swipeEnabled && canSelectPrevious(),
                        onNext = onSelectNext,
                        onPrevious = onSelectPrevious,
                    ),
            ) {
                reader(html, body.orEmpty(), article?.url, article?.url)
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
 * Displays a prebuilt HTML [html] document in a native web view.
 *
 * [html] is the complete, already-themed document to render (built by
 * [works.merc.keryx.app.ui.article.wrapArticleHtml] or one of its sibling builders); this
 * composable only owns the native WebView lifecycle. [body] is the raw article body HTML (not
 * the wrapped document) used to decide which link clicks should escape to the system browser.
 * [baseUrl] is the article's own URL (same value [html]'s `<base href>` was built from, if any)
 * used to resolve [body]'s relative `<a href>` values to the same absolute form the WebView
 * itself will navigate to.
 */
@Composable
private fun ArticleWebView(html: String, body: String, baseUrl: String?, titleUrl: String?) {
    // Only genuine outbound links from the article's own HTML are forwarded to the system
    // browser. A plain "any http(s) main-frame request" check would also catch SNS-embed
    // widgets' own internal requests (confirmed during the spike for the X/Twitter widget),
    // breaking the embed instead of letting it render in place.
    val knownLinks = remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(body, baseUrl, titleUrl) {
        val links = extractLinks(body, baseUrl.orEmpty())
        knownLinks.value = titleUrl?.takeIf { isHttpOrHttpsUrl(it) }?.let { links + it } ?: links
    }
    val scope = rememberCoroutineScope()
    val interceptor = remember {
        object : RequestInterceptor {
            override fun onInterceptUrlRequest(request: WebRequest, navigator: WebViewNavigator): WebRequestInterceptResult {
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
