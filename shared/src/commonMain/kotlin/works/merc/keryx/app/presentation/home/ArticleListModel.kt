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
 * @property writeClipboard Whether the URL is written to the clipboard.
 * @property flashCopied Whether the reader's copy button flashes its ✓ — only when the copied
 *   article is the one the reader shows. Never true without [writeClipboard].
 * @property confirmInApp Whether the UI must confirm the copy itself (Android's snackbar below API
 *   33, iOS's toast, a macOS VoiceOver announcement) — see [articleUrlCopyPlan] for the rule. Never
 *   true without [writeClipboard].
 */
data class ArticleUrlCopyPlan(val writeClipboard: Boolean, val flashCopied: Boolean, val confirmInApp: Boolean)

/**
 * The one decision behind every "copy article URL" route (reader button, keyboard shortcut, menu
 * bar, context menu) in both UIs — Compose's `ArticleUrlCopier`, SwiftUI's `ArticleUrlCopy`. An
 * unusable [url] ([hasUsableUrl]) copies nothing; a usable one is always copied; and the ✓ flashes
 * only when [articleId] is the [displayedArticleId], so it never confirms a URL other than the one
 * that button would copy.
 *
 * Whether a written copy also needs the UI's own confirmation ([ArticleUrlCopyPlan.confirmInApp]) is
 * decided here too, so no UI re-derives it:
 * - never when [platformShowsOwnConfirmation] (Android 13+ confirms every clipboard write itself —
 *   `platform/PlatformOs.kt`'s `platformShowsOwnCopyConfirmation`);
 * - otherwise, where [inlineCheckConfirms] (a desktop, whose reader is always on screen beside the
 *   list), only when the ✓ does not flash: the ✓ on the displayed article is the confirmation, but a
 *   copy of another article (a context menu opened from the keyboard or VoiceOver does not select
 *   its row) would otherwise get none;
 * - otherwise (a touch platform, where the reader and its ✓ are often off screen) on every copy.
 */
fun articleUrlCopyPlan(
    url: String?,
    articleId: String,
    displayedArticleId: String?,
    platformShowsOwnConfirmation: Boolean,
    inlineCheckConfirms: Boolean,
): ArticleUrlCopyPlan {
    if (!hasUsableUrl(url)) return ArticleUrlCopyPlan(writeClipboard = false, flashCopied = false, confirmInApp = false)
    val flashCopied = articleId == displayedArticleId
    return ArticleUrlCopyPlan(
        writeClipboard = true,
        flashCopied = flashCopied,
        confirmInApp = !platformShowsOwnConfirmation && !(inlineCheckConfirms && flashCopied),
    )
}

/**
 * Whether an article with [url] can be shared through the platform's share sheet — the single
 * enablement rule *and* guard for every route of "Share" (the reader's toolbar button, the article
 * row's context menu). The same rule as copying ([hasUsableUrl]): sharing hands the URL over as
 * plain text, exactly as a copy would, so no scheme is singled out. Whether a platform offers the
 * action at all is a separate, per-platform fact (`platform/PlatformOs.kt`'s `platformSupportsShare`).
 */
fun canShareArticleUrl(url: String?): Boolean = hasUsableUrl(url)

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
 * The read state an article has once its row's context menu is open — the state the menu's
 * "Mark as read/unread" item is labelled from and inverts. Selecting an article marks it read
 * (external-spec §7), so when opening the menu selected the row ([selectedByOpen] — a desktop
 * right-click on an unselected row) the article is read by the time the menu shows, even though the
 * row was drawn unread. Both UIs call this one definition (Compose's `articleRowMenuEntries`,
 * SwiftUI's `ArticleRowMenuState.readAfterContextMenuOpen`); each only works out whether its own
 * open selected the row.
 */
fun articleReadAfterContextMenuOpen(isRead: Boolean, selectedByOpen: Boolean): Boolean = isRead || selectedByOpen

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
