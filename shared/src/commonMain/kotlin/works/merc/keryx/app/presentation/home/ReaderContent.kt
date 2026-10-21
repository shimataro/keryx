package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.toListRow

/**
 * What the single (non-pager) article reader shows — see [HomeViewModel.readerContent].
 *
 * Decided in `:shared` so the Compose reader and the SwiftUI one switch between these at exactly
 * the same moments.
 */
sealed interface ReaderContent {
    /** Nothing is selected: the reader shows its "no article selected" placeholder. */
    data object None : ReaderContent

    /**
     * The selected article's body is not loaded and there is no reader content worth keeping in
     * its place: either nothing was loaded before it, or the body has been loading for longer than
     * [works.merc.keryx.app.core.READER_HEADER_FIRST_GRACE_MS]. The reader shows the article's
     * header — title, date, link — from its list [row], with an empty body, until it loads.
     */
    data class Header(val row: ArticleListRow) : ReaderContent

    /** The fully loaded [article], body included. */
    data class Full(val article: Articles) : ReaderContent
}

/**
 * The selected article's list row ([HomeViewModel.selectedArticleRow]): the loaded article once it
 * is the one the cursor points at, the row it was selected with ([pending]) while its body is still
 * loading, and nothing when nothing is selected.
 *
 * @param cursor The id the selection cursor points at, or `null` when nothing is selected.
 * @param hydrated The loaded article ([HomeViewModel.selectedArticle]) — still the previous one while
 *   a new selection loads.
 * @param pending The row the newest selection was made with, or `null` once its body has loaded.
 */
internal fun resolveSelectedRow(cursor: String?, hydrated: Articles?, pending: ArticleListRow?): ArticleListRow? = when {
    cursor == null -> null
    hydrated != null && hydrated.id == cursor -> hydrated.toListRow()
    pending != null && pending.id == cursor -> pending
    else -> null
}

/**
 * What the reader shows ([HomeViewModel.readerContent]):
 *
 * - the new selection's header once [headerId] says its grace period ran out with the body still
 *   loading;
 * - otherwise the loaded article — which, inside that grace period, is still the previous one, kept
 *   on screen rather than swapped for a header that would turn into the body within milliseconds;
 * - with nothing loaded to keep, the new selection's header at once: there is no previous article
 *   to hold, and "no article selected" would contradict the toolbar and the list highlight, which
 *   already follow the selection;
 * - nothing, when nothing is selected.
 *
 * @param headerId The article whose header the grace timer asked for, or `null`.
 * @see resolveSelectedRow for the other parameters.
 */
internal fun resolveReaderContent(
    cursor: String?,
    hydrated: Articles?,
    pending: ArticleListRow?,
    headerId: String?,
): ReaderContent = when {
    cursor != null && headerId == cursor && hydrated?.id != cursor && pending != null && pending.id == cursor ->
        ReaderContent.Header(pending)
    hydrated != null -> ReaderContent.Full(hydrated)
    cursor != null && pending != null && pending.id == cursor -> ReaderContent.Header(pending)
    else -> ReaderContent.None
}

/** The list row of the article this content shows, or `null` for [ReaderContent.None]. */
fun ReaderContent.listRow(): ArticleListRow? = when (this) {
    ReaderContent.None -> null
    is ReaderContent.Header -> row
    is ReaderContent.Full -> article.toListRow()
}
