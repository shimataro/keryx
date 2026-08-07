package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import com.fleeksoft.ksoup.Ksoup

/**
 * Absolute href of every `<a>` tag in [html]. Used to tell a genuine outbound link click
 * apart from a SNS-embed widget's own internal requests when both report as a main-frame
 * navigation (see plan doc html-webview-os-wobbly-hammock.md).
 */
fun extractLinks(html: String): Set<String> =
    Ksoup.parse(html).getElementsByTag("a")
        .mapNotNull { element ->
            element.attr("abs:href").ifBlank { element.attr("href") }.takeIf { it.isNotBlank() }
        }
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
 */
fun wrapArticleHtml(theme: ArticleHtmlTheme, title: String, meta: String, body: String): String =
    articleDocument(theme, articleHeader(title, meta) + body)

/**
 * Same header as [wrapArticleHtml], with a muted [message] where the body would be — for an
 * article whose feed supplied neither `content` nor `summary`. Rendered here rather than as
 * Compose text so the reader WebView is never unmounted (see `ArticleDetailPane`'s KDoc).
 */
fun articleNoContentHtml(theme: ArticleHtmlTheme, title: String, meta: String, message: String): String =
    articleDocument(theme, articleHeader(title, meta) + """<p class="article-notice">${escapeHtml(message)}</p>""")

/** [message] centered in the viewport with no header — the "no article selected" state. */
fun articlePlaceholderHtml(theme: ArticleHtmlTheme, message: String): String =
    articleDocument(
        theme,
        """<div class="article-placeholder">${escapeHtml(message)}</div>""",
        bodyClass = "placeholder",
    )

private fun articleHeader(title: String, meta: String): String = buildString {
    if (title.isNotBlank()) append("""<h1 class="article-title">${escapeHtml(title)}</h1>""")
    if (meta.isNotBlank()) append("""<div class="article-meta">${escapeHtml(meta)}</div>""")
}

/**
 * Renders [content] inside the document shell shared by every state the article reader can be
 * in. The `<style>` block is identical across all callers — this is what guarantees the
 * placeholder and "no content" notice never flash a default white page in dark mode.
 */
private fun articleDocument(theme: ArticleHtmlTheme, content: String, bodyClass: String = ""): String {
    val fontPercent = (theme.fontScale * 100).toInt()
    val bodyTag = if (bodyClass.isBlank()) "<body>" else """<body class="$bodyClass">"""
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style>
          html, body {
            margin: 0;
            padding: 16px 8px 24px;
            background-color: ${theme.surface.toCssHex()};
            color: ${theme.onSurface.toCssHex()};
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            font-size: $fontPercent%;
            line-height: 1.6;
            word-wrap: break-word;
          }
          a { color: ${theme.linkColor.toCssHex()}; }
          img, video, iframe { max-width: 100%; height: auto; }
          table { border-collapse: collapse; }
          td, th { padding: 4px 8px; }
          .article-title { font-size: 1.6em; font-weight: 600; line-height: 1.3; margin: 0 0 4px; }
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
