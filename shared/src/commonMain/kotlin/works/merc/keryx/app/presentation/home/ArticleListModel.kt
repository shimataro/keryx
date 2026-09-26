package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleListRow

/** Whether [url] is present and non-blank — the single rule for when URL-dependent actions
 * (open in browser, copy URL) are available, for an article's URL or a feed's site URL alike. */
fun hasUsableUrl(url: String?): Boolean = !url.isNullOrBlank()

/**
 * Whether the article list's "hide read articles" action has anything to do: at least one row in
 * [rows] is read and not [selectedId] (the selected article stays visible even when read, so it
 * doesn't count — hiding it would be indistinguishable from deselecting it). Used both to enable
 * the toolbar action and, after running it, to fall back to disabled once nothing is left to hide.
 */
fun hasHideableRead(rows: List<ArticleListRow>, selectedId: String?): Boolean =
    rows.any { it.is_read == 1L && it.id != selectedId }

/** Whether [url] is a plain http:// or https:// URL. Scheme matching is case-insensitive. */
fun isHttpOrHttpsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    return trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
}
