package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewStateWithHTMLData
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_copy_url
import works.merc.keryx.app.resources.article_mark_as_unread
import works.merc.keryx.app.resources.article_no_content
import works.merc.keryx.app.resources.article_no_title
import works.merc.keryx.app.resources.article_open_in_browser
import works.merc.keryx.app.resources.article_star
import works.merc.keryx.app.resources.article_unstar
import works.merc.keryx.app.resources.article_url_copied
import works.merc.keryx.app.resources.home_no_article_selected
import works.merc.keryx.app.ui.article.extractLinks
import works.merc.keryx.app.ui.article.wrapArticleHtml
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/** How long the copy button shows its inline ✓ / "copied" state before reverting. */
private const val COPIED_FEEDBACK_MS = 1500L

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
) {
    val article by vm.selectedArticle.collectAsStateSafe(null)
    val current = article

    // Inline "copied" feedback for the toolbar copy button. Kept above the early return (rather than
    // inside the copy-button block) so the pane never leaves/re-enters composition here — otherwise
    // LaunchedEffect(copyPulse) would re-fire with a stale pulse value and flash ✓ without a copy.
    var showCopied by remember { mutableStateOf(false) }
    LaunchedEffect(showCopied) {
        if (showCopied) {
            delay(COPIED_FEEDBACK_MS)
            showCopied = false
        }
    }
    // Keyboard ⌘/Ctrl+C copies the selected article (shown in this pane), so mirror the button's
    // feedback here. Initial copyPulse == 0 is skipped; only increments from HomeScreen fire it.
    LaunchedEffect(copyPulse) { if (copyPulse != 0) showCopied = true }

    if (current == null) {
        Box(
            modifier
                .background(MaterialTheme.colorScheme.surface)
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.home_no_article_selected),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val body = current.content ?: current.summary

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated),
    ) {
        WindowDragArea(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            ToolbarIconGroup {
                val starred = current.is_starred == 1L
                val starTooltip = stringResource(if (starred) Res.string.article_unstar else Res.string.article_star)
                TooltipIconButton(tooltip = starTooltip, onClick = { vm.toggleStar(current) }) {
                    KeryxIcon(
                        if (starred) KeryxIcons.Star else KeryxIcons.StarBorder,
                        contentDescription = starTooltip,
                        tint = if (starred) StarredColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val markUnreadTooltip = stringResource(Res.string.article_mark_as_unread)
                TooltipIconButton(tooltip = markUnreadTooltip, onClick = { vm.markSelectedUnread() }) {
                    KeryxIcon(KeryxIcons.Circle, contentDescription = markUnreadTooltip)
                }
                if (current.url.isNotBlank()) {
                    val clipboard = LocalClipboard.current
                    val scope = rememberCoroutineScope()
                    val copyUrlTooltip = stringResource(
                        if (showCopied) Res.string.article_url_copied else Res.string.article_copy_url,
                    )
                    TooltipIconButton(
                        tooltip = copyUrlTooltip,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipboardEntries.ofText(current.url))
                                showCopied = true
                            }
                        },
                    ) {
                        KeryxIcon(
                            if (showCopied) KeryxIcons.CheckOutlined else KeryxIcons.ContentCopy,
                            contentDescription = copyUrlTooltip,
                        )
                    }
                    val openInBrowserTooltip = stringResource(Res.string.article_open_in_browser)
                    TooltipIconButton(tooltip = openInBrowserTooltip, onClick = { BrowserOpener.open(current.url) }) {
                        KeryxIcon(KeryxIcons.PublicOutlined, contentDescription = openInBrowserTooltip)
                    }
                }
            }
        }
        }

        val title = current.title.ifBlank { stringResource(Res.string.article_no_title) }
        val meta = articleMetaText(current.author, current.published_at)

        if (body.isNullOrBlank()) {
            // No article body to scroll, so the reserved-height problem doesn't apply here — keep
            // the lightweight Compose header + "no content" text rather than spinning up a native
            // WebView just to render a single line.
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                ArticleDetailMetaLine(current.author, current.published_at)
            }
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                Text(stringResource(Res.string.article_no_content), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // Title + meta live inside the WebView HTML so they scroll away with the body; the
            // content area then uses the full pane height regardless of how long the title is.
            Box(Modifier.fillMaxSize()) {
                ArticleWebView(
                    articleId = current.id,
                    title = title,
                    meta = meta,
                    body = body,
                    mutedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
 * Displays the article author and publication time as inline metadata.
 *
 * Blank authors and unavailable publication times are omitted. Long author text is ellipsized while
 * preserving the publication time.
 *
 * @param author The article's author, if available.
 * @param publishedAt The article's publication time in Unix milliseconds, if available.
 */
@Composable
internal fun ArticleDetailMetaLine(author: String?, publishedAt: Long?, modifier: Modifier = Modifier) {
    val authorText = author?.takeIf { it.isNotBlank() }
    val timestamp = formatTimestamp(publishedAt)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth()) {
        if (authorText != null) {
            Text(
                authorText,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (timestamp.isNotEmpty()) {
            Text(
                if (authorText == null) timestamp else " · $timestamp",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
            )
        }
    }
}

/**
 * Displays an article's HTML content in a native web view.
 *
 * @param articleId The identifier of the article being displayed.
 * @param title The article title.
 * @param meta Metadata displayed with the article.
 * @param body The article HTML content.
 * @param mutedColor The color used for muted article text.
 */
@Composable
private fun ArticleWebView(articleId: String, title: String, meta: String, body: String, mutedColor: Color) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val fontScale = LocalDensity.current.fontScale

    val wrappedHtml = remember(articleId, body, surface, onSurface, linkColor, fontScale, title, meta, mutedColor) {
        wrapArticleHtml(body, surface, onSurface, linkColor, fontScale, title, meta, mutedColor)
    }

    // Only genuine outbound links from the article's own HTML are forwarded to the system
    // browser. A plain "any http(s) main-frame request" check would also catch SNS-embed
    // widgets' own internal requests (confirmed during the spike for the X/Twitter widget),
    // breaking the embed instead of letting it render in place. Title/meta carry no links, so
    // this stays keyed on the body only.
    val knownLinks = remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(articleId, body) {
        knownLinks.value = extractLinks(body)
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
    val webViewState = rememberWebViewStateWithHTMLData(data = wrappedHtml)

    // Workaround: this (beta) library's rememberWebViewStateWithHTMLData doesn't reliably
    // navigate past its initial "about:blank" on desktop — confirmed during the spike. Push
    // the HTML manually once the WebView reports it's idle.
    val loadedForArticleId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(webViewState.loadingState, articleId, wrappedHtml) {
        if (webViewState.loadingState is LoadingState.Finished && loadedForArticleId.value != articleId) {
            loadedForArticleId.value = articleId
            navigator.loadHtml(wrappedHtml)
        }
    }

    // The native webview (a heavyweight AWT component wrapping a real OS-native browser
    // window, not a Compose-composited texture) briefly paints at its default (0,0) position
    // before Compose's SwingPanel interop applies the real layout bounds — a known Compose
    // Desktop interop limitation (JetBrains YouTrack CMP-5780), not specific to this app. Hide
    // the panel from the moment it's created and only reveal it once this composable's own
    // layout position is known, giving Compose's own bounds sync a frame to catch up first.
    // Revealed once, not on every article switch, since the panel is reused after creation.
    val nativePanel = remember { mutableStateOf<WryWebViewPanel?>(null) }
    val hasPositioned = remember { mutableStateOf(false) }
    val revealed = remember { mutableStateOf(false) }
    LaunchedEffect(hasPositioned.value, nativePanel.value) {
        if (hasPositioned.value && nativePanel.value != null && !revealed.value) {
            withFrameNanos {}
            revealed.value = true
            nativePanel.value?.isVisible = true
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
            panel.isVisible = false
            nativePanel.value = panel
        },
    )
}
