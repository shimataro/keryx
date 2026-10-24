package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.ArticleSearchResult

/**
 * The unread pins among a read-state pin map. Always kept by a re-trim: an unread pin never keeps
 * a row on screen by membership (its row passes the unread-only filter on its own is_read == 0),
 * but until its "mark as unread" write lands the raw query still says read, and dropping it would
 * make an unread-only list lose the row for that window (see [HomeViewModel]'s setRead).
 */
internal fun Map<String, ArticleListRow>.unreadPins(): Map<String, ArticleListRow> = filterValues { it.is_read == 0L }

/**
 * The read pins among a read-state pin map — the only ones that keep a row on screen by
 * membership, and so the only ones [resolvePinnedArticles] re-adds a row for.
 */
internal fun Map<String, ArticleListRow>.readPins(): Map<String, ArticleListRow> = filterValues { it.is_read == 1L }

/**
 * The article list [HomeViewModel] shows for one raw query emission [list], with the optimistic
 * pins applied: [pinnedReadStates] (read state the user just set) and [pinnedUnstarred] (articles
 * unstarred while browsing Starred). Then filtered to unread when [unread], and ordered newest
 * first unless [newest] is false.
 */
internal fun resolvePinnedArticles(
    list: List<ArticleListRow>,
    unread: Boolean,
    newest: Boolean,
    pinnedReadStates: Map<String, ArticleListRow>,
    pinnedUnstarred: Map<String, ArticleListRow>,
): List<ArticleListRow> {
    // Nothing pinned is the common case, and then neither a resolved copy nor the id set has
    // a reader — skip building either rather than touching every row on every emission.
    val resolvedList: List<ArticleListRow>
    val extra: List<ArticleListRow>
    if (pinnedReadStates.isEmpty() && pinnedUnstarred.isEmpty()) {
        resolvedList = list
        extra = emptyList()
    } else {
        // A pinned article can already be present in `list` — its own optimistic write just
        // hasn't reached the raw query result yet (dbWriteDispatcher runs it asynchronously).
        // Without this, the row would show the pre-toggle field for that window, exactly the
        // gap the pin exists to paper over (e.g. is_starred still 1 for an article just
        // unstarred while browsing Starred). Per-field resolution (rather than picking one
        // map's snapshot outright) covers an article pinned in both at once, e.g. read and
        // then unstarred while browsing Starred + unread-only.
        //
        // Only a pinned row whose resolved fields differ is replaced; every other row keeps
        // its instance, and once the writes have landed the raw list itself is returned.
        var resolved: MutableList<ArticleListRow>? = null
        val presentPinnedIds = HashSet<String>()
        list.forEachIndexed { index, row ->
            val readStatePin = pinnedReadStates[row.id]
            val unstarPin = pinnedUnstarred[row.id]
            if (readStatePin == null && unstarPin == null) return@forEachIndexed
            presentPinnedIds += row.id
            val isRead = readStatePin?.is_read ?: row.is_read
            val isStarred = unstarPin?.is_starred ?: row.is_starred
            if (isRead != row.is_read || isStarred != row.is_starred) {
                val target = resolved ?: list.toMutableList().also { resolved = it }
                target[index] = row.copy(is_read = isRead, is_starred = isStarred)
            }
        }
        resolvedList = resolved ?: list
        // An unread pin only corrects a row the raw query still returns — while its write is in
        // flight the row is still there, as read — and never re-adds one: a row the filter no
        // longer returns (unstarred, moved out of the folder/tag) must leave rather than linger.
        // That includes the selection: marked unread after leaving the query, it leaves the list
        // while the reader keeps showing it.
        val missingPinnedIds = (pinnedReadStates.readPins().keys + pinnedUnstarred.keys) - presentPinnedIds
        extra = missingPinnedIds.map { id ->
            // A read-state pin, when there is one, already carries the row's read state.
            val base = pinnedReadStates[id] ?: pinnedUnstarred.getValue(id)
            pinnedUnstarred[id]?.let { base.copy(is_starred = it.is_starred) } ?: base
        }
    }
    val merged = if (extra.isEmpty()) resolvedList else (resolvedList + extra).sortedWith(
        compareByDescending<ArticleListRow> { it.published_at ?: 0L }
            .thenByDescending { it.created_at }
            .thenByDescending { it.id }
    )
    // Independent of the resolution above: a row with a read pin was resolved to is_read == 1,
    // so this OR is what keeps a just-marked-read article visible for its grace period under
    // unread-only — id membership, not staleness, is what this needs. A row with an unread pin
    // was resolved to is_read == 0 and passes the first clause.
    val filtered = if (unread) {
        merged.filter { it.is_read == 0L || it.id in pinnedReadStates }
    } else {
        merged
    }
    // asReversed() is a view, not a second full copy: `filtered` is freshly derived
    // per emission and never mutated afterwards, so it reads identically.
    return if (newest) filtered else filtered.asReversed()
}

/**
 * The search results [HomeViewModel] shows for one raw search emission [raw], with the optimistic
 * pins applied and filtered to unread when [unread].
 *
 * Applies only the optimistic read-state from pinned (never the whole snapshot): other fields —
 * notably is_starred — must come from the fresh re-search, or starring an already-read result
 * would be hidden by the stale pinned copy. Field-wise merge of `is_read` from [pinnedReadStates]
 * and `is_starred` from [pinnedUnstarred], matching [resolvePinnedArticles]. Unlike that one, this
 * never merges pinned-but-absent-from-raw articles back in.
 */
internal fun resolvePinnedSearchResults(
    raw: List<ArticleSearchResult>,
    unread: Boolean,
    pinnedReadStates: Map<String, ArticleListRow>,
    pinnedUnstarred: Map<String, ArticleListRow>,
): List<ArticleSearchResult> {
    val merged = raw.map { result ->
        val readStatePin = pinnedReadStates[result.article.id]
        val unstarPin = pinnedUnstarred[result.article.id]
        if (readStatePin == null && unstarPin == null) {
            result
        } else {
            result.copy(
                article = result.article.copy(
                    is_read = readStatePin?.is_read ?: result.article.is_read,
                    is_starred = unstarPin?.is_starred ?: result.article.is_starred,
                ),
            )
        }
    }
    // As in resolvePinnedArticles: a read pin keeps its row by membership, an unread pin's row
    // passes on its own resolved is_read == 0.
    return if (unread) merged.filter { it.article.is_read == 0L || it.article.id in pinnedReadStates } else merged
}
