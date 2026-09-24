package works.merc.keryx.app.ui.home

/**
 * Tracks which article ids have appeared in the current filter's raw query result but have not yet
 * scrolled into the article list's viewport — the "new articles" pill's own state machine (see
 * `ArticleListPane`/`NewArticlesPill`).
 *
 * Driven from the *raw* per-filter query result (`HomeViewModel.filteredArticles`), not the display
 * list (`HomeViewModel.articles`): the raw query is unaffected by the unread-only toggle or sort
 * direction, so toggling either never manufactures a false "new article". A filter change instead
 * replaces this whole instance (`HomeViewModel`'s `onStart { ... }` on the newly-selected filter's
 * flow), so an existing article under the new filter is never mistaken for new.
 *
 * @param knownIds The full id set from the most recent raw query result. `null` until the first
 *   emission — that first emission only seeds [knownIds] and adds nothing to [unseenIds], since
 *   every article in it was already there before tracking started (there is nothing to have missed
 *   yet).
 * @param unseenIds Ids that appeared in a later emission than the one that seeded [knownIds] and
 *   have not yet been reported as visible via [withVisible].
 */
internal data class NewArticleTracking(
    val knownIds: Set<String>? = null,
    val unseenIds: Set<String> = emptySet(),
)

/**
 * Folds in a fresh raw query result. The first call after a reset only seeds [NewArticleTracking.knownIds]
 * (nothing is "new" relative to a baseline that didn't exist yet); every later call adds whatever
 * ids are in [ids] but weren't in the previous [NewArticleTracking.knownIds], and drops any
 * previously-unseen id that fell out of [ids] (deleted, unsubscribed, or filtered out by the query
 * itself — e.g. sync-merge propagating a tombstone).
 */
internal fun NewArticleTracking.withList(ids: Set<String>): NewArticleTracking {
    val previouslyKnown = knownIds
    val nextUnseen = if (previouslyKnown == null) {
        unseenIds
    } else {
        (unseenIds + (ids - previouslyKnown)).intersect(ids)
    }
    return copy(knownIds = ids, unseenIds = nextUnseen)
}

/** Drops [ids] from [NewArticleTracking.unseenIds] — the list's own report of what's now on screen. */
internal fun NewArticleTracking.withVisible(ids: Set<String>): NewArticleTracking =
    if (unseenIds.isEmpty()) this else copy(unseenIds = unseenIds - ids)

/** Clears every unseen id — the pill's own tap action. */
internal fun NewArticleTracking.allSeen(): NewArticleTracking =
    if (unseenIds.isEmpty()) this else copy(unseenIds = emptySet())
