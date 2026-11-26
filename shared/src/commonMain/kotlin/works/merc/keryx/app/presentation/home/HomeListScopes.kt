package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleSearchResult
import works.merc.keryx.app.domain.TagFolderIdRemap

// The list-scope and snapshot types HomeViewModel's article list and search flows are built from.

/**
 * Debounced FTS results tagged with the query/filter that produced them (see
 * [HomeViewModel.searching]).
 */
internal data class SearchSnapshot(
    val query: String,
    val filter: ArticleFilter,
    val results: List<ArticleSearchResult>,
)

/**
 * The filter and narrowing a displayed list was produced from. The list's flows catch up with a
 * filter or mode change asynchronously, so for a moment the rows on screen can still belong to the
 * previous one — [HomeViewModel.markAllRead] waits until this matches the current selection.
 */
internal data class ListScope(val filter: ArticleFilter, val narrowing: ListNarrowing) {
    /** This scope with its tag or folder id brought up to date on [remap]. */
    fun remappedBy(remap: TagFolderIdRemap): ListScope =
        remap.filter(filter).let { if (it == filter) this else copy(filter = it) }
}

/** Rows tagged with the [ListScope] that produced them. */
internal data class ScopedRows<T>(val rows: List<T>, val scope: ListScope)

/**
 * The article list's selected [filter], with the [generation] of the user's selection that put it
 * there ([HomeViewModel.filterSelection]). The generation moves only when the user switches to
 * another filter; a merge moving the selected tag or folder onto another id ([HomeViewModel]'s
 * re-key handling) changes [filter] and keeps [generation], since it is the same list under a new
 * id. Every UI reaction to a switch — the list back to the top, a pull's indicator ending, the
 * new-articles pill starting afresh — keys on [generation], so a re-key causes none of them
 * (external-spec §9, "Least surprise"). The generation counts from 0 at each ViewModel's creation:
 * it is meaningful only against an earlier value of the same ViewModel, never across a launch.
 */
data class FilterSelection(val filter: ArticleFilter, val generation: Long)

/**
 * [HomeViewModel.articles] together with the filter its rows were produced from (null before the
 * list first loads). `filter` and `articles` are separate flows whose emissions can reach a UI in
 * either order, so a UI that acts on a filter switch against the rows — the iOS list scrolling back
 * to the top — waits for rows whose [filter] is the new one rather than acting on the previous
 * filter's rows.
 */
class FilteredArticleRows internal constructor(val filter: ArticleFilter?, val rows: List<ArticleListRow>)

/** [HomeViewModel.searchResults] together with the filter the search was scoped to — see [FilteredArticleRows]. */
class FilteredSearchResults internal constructor(val filter: ArticleFilter, val rows: List<ArticleSearchResult>)

/**
 * What the article list shows for a search — its rows and their title highlights — built from one
 * snapshot so the two never disagree. Compared by identity: a new instance is a new snapshot.
 */
class SearchListDisplay internal constructor(results: List<ArticleSearchResult>) {
    val rows: List<ArticleListRow> = results.map { it.article }
    val titleMarkedById: Map<String, String> = results.associate { it.article.id to it.titleMarked }
}

/**
 * Search results tagged with the [ListScope] that produced them, plus what the article list shows
 * for them. The [display] is built once here, when the snapshot is produced, rather than on every
 * read of the list on screen — the reader's swipe asks for its rows on every drag event. [display]
 * is a body property, so it stays out of [equals]: it is derived from [rows] alone.
 */
internal data class ScopedSearchResults(val rows: List<ArticleSearchResult>, val scope: ListScope) {
    val display: SearchListDisplay = SearchListDisplay(rows)
}

internal fun narrowingFor(mode: ArticleListMode) =
    ListNarrowing(unreadOnly = mode == ArticleListMode.Unread, starredOnly = mode == ArticleListMode.Starred)
