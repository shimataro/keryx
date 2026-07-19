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
 * Wraps article [body] HTML in a minimal document that applies the current app theme
 * (background/text/link colors, font scale) so the WebView doesn't flash a default
 * white/black page before/instead of matching the surrounding UI.
 *
 * [title] and [meta] (author · date) are rendered as a header before the body so they scroll
 * together with it — this is what keeps a long title from permanently shrinking the content
 * area. They are plain feed text, so they are HTML-escaped; [body] stays raw (trusted rich HTML).
 * [mutedColor] tints the meta line (onSurfaceVariant).
 */
fun wrapArticleHtml(
    body: String,
    surface: Color,
    onSurface: Color,
    linkColor: Color,
    fontScale: Float,
    title: String,
    meta: String,
    mutedColor: Color,
): String {
    val fontPercent = (fontScale * 100).toInt()
    val header = buildString {
        if (title.isNotBlank()) append("""<h1 class="article-title">${escapeHtml(title)}</h1>""")
        if (meta.isNotBlank()) append("""<div class="article-meta">${escapeHtml(meta)}</div>""")
    }
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
            background-color: ${surface.toCssHex()};
            color: ${onSurface.toCssHex()};
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
            font-size: $fontPercent%;
            line-height: 1.6;
            word-wrap: break-word;
          }
          a { color: ${linkColor.toCssHex()}; }
          img, video, iframe { max-width: 100%; height: auto; }
          table { border-collapse: collapse; }
          td, th { padding: 4px 8px; }
          .article-title { font-size: 1.6em; font-weight: 600; line-height: 1.3; margin: 0 0 4px; }
          .article-meta { font-size: 0.85em; color: ${mutedColor.toCssHex()}; margin: 0 0 16px; }
        </style>
        </head>
        <body>
        $header
        $body
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
