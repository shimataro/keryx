package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleListRow

/** Whether [url] is present and non-blank — the single rule for when "copy URL" is available, on
 * every route, for an article's URL or a feed's site URL alike. Opening a URL in the browser has its
 * own, stricter rule: [canOpenInBrowser]. */
fun hasUsableUrl(url: String?): Boolean = !url.isNullOrBlank()

/**
 * What one "copy article URL" does — the decision [articleUrlCopyPlan] makes for every route and both
 * UIs; each UI only carries it out.
 *
 * @property writeClipboard Whether the URL is written to the clipboard (and the platform's own
 *   copy feedback, e.g. Android's snackbar, follows it).
 * @property flashCopied Whether the reader's copy button flashes its ✓ — only when the copied
 *   article is the one the reader shows. Never true without [writeClipboard].
 */
data class ArticleUrlCopyPlan(val writeClipboard: Boolean, val flashCopied: Boolean)

/**
 * The one decision behind every "copy article URL" route (reader button, keyboard shortcut, menu
 * bar, context menu) in both UIs — Compose's `ArticleUrlCopier`, SwiftUI's `ArticleUrlCopy`. An
 * unusable [url] ([hasUsableUrl]) copies nothing; a usable one is always copied; and the ✓ flashes
 * only when [articleId] is the [displayedArticleId], so it never confirms a URL other than the one
 * that button would copy.
 */
fun articleUrlCopyPlan(url: String?, articleId: String, displayedArticleId: String?): ArticleUrlCopyPlan {
    if (!hasUsableUrl(url)) return ArticleUrlCopyPlan(writeClipboard = false, flashCopied = false)
    return ArticleUrlCopyPlan(writeClipboard = true, flashCopied = articleId == displayedArticleId)
}

/**
 * Whether [url] may be opened in the external browser — the single enablement rule *and* guard for
 * every route of "Open in browser" (toolbar button, menu bar, keyboard shortcut, context menu), for
 * an article's URL or a feed's site URL alike: only http(s) qualifies.
 *
 * Security: an article's URL (and a feed's site URL) is unvalidated input from the feed, and
 * `BrowserOpener` hands whatever it gets to the OS (`open` / `rundll32` / `xdg-open` /
 * `ACTION_VIEW`), so a `file:`, `javascript:` or custom-scheme link must never reach it.
 * `BrowserOpener` itself stays unrestricted because the app's own fixed links use other schemes
 * (Settings' `mailto:`). A relative or scheme-less link is rejected too; copying it is still
 * allowed ([hasUsableUrl]).
 */
fun canOpenInBrowser(url: String?): Boolean = isHttpOrHttpsUrl(url)

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
