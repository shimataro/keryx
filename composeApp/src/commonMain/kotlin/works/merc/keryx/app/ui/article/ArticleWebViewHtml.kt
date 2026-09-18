package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.fleeksoft.ksoup.Ksoup
import works.merc.keryx.app.data.remote.UrlResolver
import works.merc.keryx.app.ui.home.isHttpOrHttpsUrl

/**
 * Absolute href of every `<a>` tag in [html], resolved against [baseUri] (the article's own URL)
 * via [UrlResolver.resolve]. Used to tell a genuine outbound link click apart from a SNS-embed
 * widget's own internal requests when both report as a main-frame navigation (see
 * "Article Reader (native WebView)" in app-architecture.md). An absolute href resolves the same
 * regardless of [baseUri];
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
 * Whether the document should declare a dark `color-scheme`, derived from [ArticleHtmlTheme.surface]'s
 * own luminance rather than from the app's `themeMode` setting: the reader has no access to
 * `resolveDarkTheme`'s inputs (see `ui/theme/KeryxTheme.kt`), and deriving it from the color the
 * page is actually painted with is strictly more correct anyway — it follows Android's dynamic
 * Material You palette (which can be dark at a luminance the fixed teal scheme never produces)
 * without a second source of truth.
 */
private val ArticleHtmlTheme.isDark: Boolean
    get() = surface.luminance() < 0.5f

/**
 * Wraps article [body] HTML in a minimal document that applies [theme] (background/text/link
 * colors, font scale) so the WebView doesn't flash a default white/black page before/instead of
 * matching the surrounding UI.
 *
 * [title] and [meta] (author · date) are rendered as a header before the body so they scroll
 * together with it — this is what keeps a long title from permanently shrinking the content
 * area. They are plain feed text, so they are HTML-escaped; [body] stays raw, so its rich markup
 * renders — but it is third-party content, not trusted markup: see [articleDocument] for the CSP
 * that stops it pulling in the source site's own stylesheets, and for how far the `!important`
 * chrome rules do — and don't — hold against inline styles it carries itself.
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
 *
 * The shell also declares a `style-src 'unsafe-inline'` CSP, which stops a feed body from
 * loading the source site's own stylesheets over this one while leaving its scripts (and the SNS
 * embeds that need them) unrestricted. See the comment on the document builder below.
 */
private fun articleDocument(theme: ArticleHtmlTheme, content: String, bodyClass: String = "", baseUrl: String? = null): String {
    val fontPercent = (theme.fontScale * 100).toInt()
    val colorScheme = if (theme.isDark) "dark" else "light"
    val bodyTag = if (bodyClass.isBlank()) "<body>" else """<body class="$bodyClass">"""
    val baseTag = baseUrl?.takeIf { it.isNotBlank() }?.let { """<base href="${escapeHtml(it)}" />""" }.orEmpty()
    // Two layers of defense against a feed body restyling the reader around it. A body is embedded raw
    // (see wrapArticleHtml) below a base element at the article's own origin, so it can pull in
    // the source site's stylesheets — statically via a stylesheet link, or by appending one to
    // the head from its own script. Such a sheet arrives after the first paint and, being later
    // in the cascade, would win over every rule in the style block below.
    //
    // 1. The CSP is the actual defense: it names no URL source for style-src, so no external
    //    stylesheet is ever fetched, while 'unsafe-inline' keeps the style block below (and the
    //    body's own style attributes) working. No script-src and no default-src are declared, so
    //    the body's JavaScript — and the SNS embeds that need it — are left untouched.
    // 2. The !important declarations harden the reader's own chrome against a lower-specificity
    //    override — an engine that ignores a meta CSP, or the ordinary inline style element the
    //    body carries (which 'unsafe-inline' still admits). That is hardening, not isolation,
    //    and it covers only the declarations actually listed below: a property none of them
    //    marks !important (.article-title { display: none !important }) goes straight through,
    //    and an equally-or-more-specific !important rule of the body's own wins on document
    //    order, since the body follows this style block. Containing the body outright would take
    //    sanitization or a separate rendering boundary — see "What a real fix would need" in
    //    known-issues.md. The limit to chrome is itself deliberate: the content-facing rules (a,
    //    img/video/iframe, table, td/th) stay plain defaults that a feed author's own style
    //    attribute is meant to be able to override, exactly as it can today.
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8" />
        <meta http-equiv="Content-Security-Policy" content="style-src 'unsafe-inline'" />
        $baseTag
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style>
          html {
            /* Declares a single value (never `light dark`) so Chromium/WebKit paints its own
               form controls and scrollbars in the app's own theme rather than following the OS
               setting independently — the in-app light/dark setting is authoritative here, not
               the OS's. Deliberately no vendor-prefixed or standard scrollbar-styling rule below:
               defining one switches the browser off its overlay scrollbar and onto a classic,
               layout-consuming one, narrowing the article body (see ArticleWebViewHtmlTest's
               regression test for this). */
            color-scheme: $colorScheme !important;
            font-size: $fontPercent% !important;
          }
          html, body {
            margin: 0 !important;
            padding: 16px 8px 24px !important;
            background-color: ${theme.surface.toCssHex()} !important;
            color: ${theme.onSurface.toCssHex()} !important;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif !important;
            line-height: 1.6 !important;
            word-wrap: break-word !important;
          }
          a { color: ${theme.linkColor.toCssHex()}; }
          img, video, iframe { max-width: 100%; height: auto; }
          table { border-collapse: collapse; }
          td, th { padding: 4px 8px; }
          .article-title { font-size: 1.6em !important; font-weight: 600 !important; line-height: 1.3 !important; margin: 0 0 4px !important; }
          .article-title a { color: inherit !important; text-decoration: none !important; cursor: pointer !important; transition: opacity 0.15s ease !important; }
          .article-title a:hover { opacity: 0.7 !important; }
          .article-title a:active { opacity: 0.5 !important; }
          .article-meta { font-size: 0.85em !important; color: ${theme.mutedColor.toCssHex()} !important; margin: 0 0 16px !important; }
          .article-notice { color: ${theme.mutedColor.toCssHex()} !important; margin: 0 !important; }
          .article-placeholder {
            position: fixed !important;
            inset: 0 !important;
            display: flex !important;
            align-items: center !important;
            justify-content: center !important;
            box-sizing: border-box !important;
            padding: 16px !important;
            text-align: center !important;
            color: ${theme.mutedColor.toCssHex()} !important;
          }
          body.placeholder { overflow: hidden !important; }
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
