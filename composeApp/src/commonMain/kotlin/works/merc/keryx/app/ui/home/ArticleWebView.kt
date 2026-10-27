package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.NativeWebView
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewStateWithHTMLData
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.isNativeWebViewScreenReaderActive
import works.merc.keryx.app.platform.NativeWebViewScrollListener
import works.merc.keryx.app.platform.isNativeWebViewSupported
import works.merc.keryx.app.platform.nativeWebViewContentHeight
import works.merc.keryx.app.platform.observeNativeWebViewScroll
import works.merc.keryx.app.platform.readerWebViewFactory
import works.merc.keryx.app.platform.scrollNativeWebViewTo
import works.merc.keryx.app.platform.setNativeWebViewImportantForAccessibility
import works.merc.keryx.app.platform.setNativeWebViewScrollbarColor
import works.merc.keryx.app.platform.setNativeWebViewVisible
import works.merc.keryx.app.presentation.home.isHttpOrHttpsUrl
import works.merc.keryx.app.ui.article.ArticleContentView
import works.merc.keryx.app.presentation.article.ReaderScrollRestoration
import works.merc.keryx.app.presentation.article.extractLinks
import works.merc.keryx.app.presentation.article.wrapArticleHtml
import kotlinx.coroutines.delay

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
internal fun ArticleWebView(html: String, body: String, articleUrl: String?, active: Boolean) {
    // Some platform/architecture pairs have no native web view binary at all, and letting the
    // library's UnsatisfiedLinkError escape composition freezes the window (see
    // isNativeWebViewSupported). Draw the article with Compose there instead. The value cannot
    // change while the app runs, so returning early never reshapes an existing composition.
    if (!isNativeWebViewSupported()) {
        ArticleContentView(html = html, modifier = Modifier.fillMaxSize(), active = active)
        return
    }

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
    // The reading position, saved across the process being killed in the background (Android) so
    // the document comes back where the user left it — see ReaderScrollRestoration. Keyed by the
    // document's HTML rather than an article id, for the same reason as the load guard below. Held
    // outside the renderer key below, so a WebView rebuilt after its renderer died returns there too.
    val scrollRestoration = rememberSaveable(saver = ReaderScrollRestorationSaver) { ReaderScrollRestoration() }
    // Android's WebView renderer process can die under a live app (the OS reclaiming memory, a
    // renderer crash). Its WebView is then unusable, so the whole native view — and everything bound
    // to it below — is rebuilt under a new key, reloading the document and returning to the reading
    // position (see readerWebViewFactory). Every reader WebView shares the one renderer, so each
    // carousel slot rebuilds its own.
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val webViewFactory = remember {
        readerWebViewFactory(
            onRenderProcessGone = {
                scrollRestoration.reloadStarted()
                rendererGeneration++
            },
        )
    }
    key(rendererGeneration) {
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
        val nativePanel = remember { mutableStateOf<NativeWebView?>(null) }
        LaunchedEffect(webViewState.loadingState, html) {
            if (shouldLoadArticleHtml(webViewState.loadingState, loadedHtml.value, html)) {
                scrollRestoration.documentReplaced(readerDocumentKey(html))
                loadedHtml.value = html
                navigator.loadHtml(html)
            } else if (webViewState.loadingState is LoadingState.Finished && loadedHtml.value == html) {
                nativePanel.value?.let { restoreReaderScroll(it, scrollRestoration) }
            }
        }

        // The native webview (a heavyweight AWT component wrapping a real OS-native browser
        // window, not a Compose-composited texture) briefly paints at its default (0,0) position
        // before Compose's SwingPanel interop applies the real layout bounds — a known Compose
        // Desktop interop limitation (JetBrains YouTrack CMP-5780), not specific to this app. Hide
        // the panel from the moment it's created and only reveal it once this composable's own
        // layout position is known, giving Compose's own bounds sync a frame to catch up first.
        // Revealed once, not on every article switch, since the panel is reused after creation.
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
            factory = webViewFactory,
            onCreated = { panel ->
                setNativeWebViewVisible(panel, false)
                setNativeWebViewImportantForAccessibility(panel, active)
                observeNativeWebViewScroll(panel, readerScrollListener(scrollRestoration))
                nativePanel.value = panel
            },
        )
    }
}

/** Feeds the reader WebView's scrolling and the user's input to [restoration]. */
private fun readerScrollListener(restoration: ReaderScrollRestoration) = object : NativeWebViewScrollListener {
    override fun onScrolled(fraction: Double) {
        restoration.scrolled(fraction, readerScrollNowMillis())
    }

    override fun onUserInput() = restoration.userInteracted()
}

/** How often a settling restore checks whether the document grew. */
private const val READER_SCROLL_GROWTH_POLL_MS = 100L

/**
 * Scrolls [webView]'s just-finished document to the position [restoration] restores, if any, then
 * re-applies it each time the content grows until the restore ends (the settle window passes or the
 * user takes over) — except while a screen reader is running, which gets the position once only.
 * Runs in the load effect, so a newer document cancels it.
 */
private suspend fun restoreReaderScroll(webView: NativeWebView, restoration: ReaderScrollRestoration) {
    val target = restoration.documentFinished(
        nowMillis = readerScrollNowMillis(),
        reapplyOnGrowth = !isNativeWebViewScreenReaderActive(webView),
    ) ?: return
    scrollNativeWebViewTo(webView, target)
    var height = nativeWebViewContentHeight(webView)
    while (restoration.isRestoring(readerScrollNowMillis())) {
        delay(READER_SCROLL_GROWTH_POLL_MS)
        val current = nativeWebViewContentHeight(webView)
        if (current == height) continue
        height = current
        restoration.contentResized(readerScrollNowMillis())?.let { scrollNativeWebViewTo(webView, it) }
    }
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
