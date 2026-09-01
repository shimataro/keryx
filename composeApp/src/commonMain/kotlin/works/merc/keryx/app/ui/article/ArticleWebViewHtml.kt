package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import com.fleeksoft.ksoup.Ksoup
import works.merc.keryx.app.data.remote.UrlResolver
import works.merc.keryx.app.ui.home.isHttpOrHttpsUrl

/**
 * Absolute href of every `<a>` tag in [html], resolved against [baseUri] (the article's own URL)
 * via [UrlResolver.resolve]. Used to tell a genuine outbound link click apart from a SNS-embed
 * widget's own internal requests when both report as a main-frame navigation (see plan doc
 * html-webview-os-wobbly-hammock.md). An absolute href resolves the same regardless of [baseUri];
 * a relative href is dropped when it can't be resolved (no usable [baseUri]) rather than kept raw,
 * since an unresolved relative string can never match the WebView's own absolutely-resolved
 * navigation request.
 */
fun extractLinks(html: String, baseUri: String = ""): Set<String> =
    Ksoup.parse(html).getElementsByTag("a")
        .mapNotNull { element -> element.attr("href").takeIf { it.isNotBlank() } }
        .mapNotNull { href -> UrlResolver.resolve(baseUri, href) }
        .toSet()

/**
 * The app-theme inputs shared by every document the article reader's WebView renders. Bundled so
 * the placeholder, the "no content" notice and a real article are guaranteed to share the same
 * background/text/link colors and font scale as the surrounding Compose pane.
 */
data class ArticleHtmlTheme(
    val surface: Color,
    val onSurface: Color,
    val linkColor: Color,
    val mutedColor: Color,
    val fontScale: Float,
)

/**
 * Wraps article [body] HTML in a minimal document that applies [theme] (background/text/link
 * colors, font scale) so the WebView doesn't flash a default white/black page before/instead of
 * matching the surrounding UI.
 *
 * [title] and [meta] (author · date) are rendered as a header before the body so they scroll
 * together with it — this is what keeps a long title from permanently shrinking the content
 * area. They are plain feed text, so they are HTML-escaped; [body] stays raw (trusted rich HTML).
 *
 * [baseUrl], when non-blank, is the article's own URL and is emitted as a `<base href>` so
 * relative `src`/`href` values inside [body] (relative images, links) resolve against the
 * article's origin instead of failing to resolve at all. Left `null`/blank, no `<base>` tag is
 * emitted — an empty `<base href="">` would resolve to the document's own (meaningless) URL.
 */
fun wrapArticleHtml(
    theme: ArticleHtmlTheme,
    title: String,
    meta: String,
    body: String,
    baseUrl: String? = null,
    titleUrl: String? = null,
    titleTooltip: String? = null,
): String = articleDocument(theme, articleHeader(title, meta, titleUrl, titleTooltip) + body, baseUrl = baseUrl)

/**
 * Same header as [wrapArticleHtml], with a muted [message] where the body would be — for an
 * article whose feed supplied neither `content` nor `summary`. Rendered here rather than as
 * Compose text so the reader WebView is never unmounted (see `ArticleDetailPane`'s KDoc).
 */
fun articleNoContentHtml(
    theme: ArticleHtmlTheme,
    title: String,
    meta: String,
    message: String,
    titleUrl: String? = null,
    titleTooltip: String? = null,
): String = articleDocument(theme, articleHeader(title, meta, titleUrl, titleTooltip) + """<p class="article-notice">${escapeHtml(message)}</p>""")

/** [message] centered in the viewport with no header — the "no article selected" state. */
fun articlePlaceholderHtml(theme: ArticleHtmlTheme, message: String): String =
    articleDocument(
        theme,
        """<div class="article-placeholder">${escapeHtml(message)}</div>""",
        bodyClass = "placeholder",
    )

private fun articleHeader(
    title: String,
    meta: String,
    titleUrl: String? = null,
    titleTooltip: String? = null,
): String = buildString {
    if (title.isNotBlank()) {
        if (titleUrl != null && isHttpOrHttpsUrl(titleUrl)) {
            val tooltipAttr = titleTooltip?.takeIf { it.isNotBlank() }?.let { " title=\"${escapeHtml(it)}\"" }.orEmpty()
            append("""<h1 class="article-title"><a href="${escapeHtml(titleUrl)}"$tooltipAttr>${escapeHtml(title)}</a></h1>""")
        } else {
            append("""<h1 class="article-title">${escapeHtml(title)}</h1>""")
        }
    }
    if (meta.isNotBlank()) append("""<div class="article-meta">${escapeHtml(meta)}</div>""")
}

/**
 * Renders [content] inside the document shell shared by every state the article reader can be
 * in. The `<style>` block is identical across all callers — this is what guarantees the
 * placeholder and "no content" notice never flash a default white page in dark mode.
 */
private fun articleDocument(theme: ArticleHtmlTheme, content: String, bodyClass: String = "", baseUrl: String? = null): String {
    val fontPercent = (theme.fontScale * 100).toInt()
    val bodyTag = if (bodyClass.isBlank()) "<body>" else """<body class="$bodyClass">"""
    val baseTag = baseUrl?.takeIf { it.isNotBlank() }?.let { """<base href="${escapeHtml(it)}" />""" }.orEmpty()
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8" />
        $baseTag
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style>
          html {
            font-size: $fontPercent%;
          }
          html, body {
            margin: 0;
            padding: 16px 8px 24px;
            background-color: ${theme.surface.toCssHex()};
            color: ${theme.onSurface.toCssHex()};
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            line-height: 1.6;
            word-wrap: break-word;
          }
          a { color: ${theme.linkColor.toCssHex()}; }
          img, video, iframe { max-width: 100%; height: auto; }
          table { border-collapse: collapse; }
          td, th { padding: 4px 8px; }
          .article-title { font-size: 1.6em; font-weight: 600; line-height: 1.3; margin: 0 0 4px; }
          .article-title a { color: inherit; text-decoration: none; cursor: pointer; transition: opacity 0.15s ease; }
          .article-title a:hover { opacity: 0.7; }
          .article-title a:active { opacity: 0.5; }
          .article-meta { font-size: 0.85em; color: ${theme.mutedColor.toCssHex()}; margin: 0 0 16px; }
          .article-notice { color: ${theme.mutedColor.toCssHex()}; margin: 0; }
          .article-placeholder {
            position: fixed;
            inset: 0;
            display: flex;
            align-items: center;
            justify-content: center;
            box-sizing: border-box;
            padding: 16px;
            text-align: center;
            color: ${theme.mutedColor.toCssHex()};
          }
          body.placeholder { overflow: hidden; }
        </style>
        </head>
        $bodyTag
        $content
        </body>
        </html>
    """.trimIndent()
}

/**
 * Escapes plain text for safe inclusion as HTML text content. `&` is replaced first so the
 * entity ampersands introduced by the later replacements aren't double-escaped.
 */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

internal fun Color.toCssHex(): String =
    "#${(red * 255).toInt().toHex2()}${(green * 255).toInt().toHex2()}${(blue * 255).toInt().toHex2()}"

private fun Int.toHex2(): String {
    val hexChars = "0123456789abcdef"
    val v = coerceIn(0, 255)
    return "${hexChars[(v shr 4) and 0xF]}${hexChars[v and 0xF]}"
}
