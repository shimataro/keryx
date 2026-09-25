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
 *   emission, and treated exactly like an empty one — see [withList] for why the two are the same
 *   thing as far as "what could the user have missed" goes.
 * @param unseenIds Ids that appeared in an emission later than the one that established a non-empty
 *   [knownIds] baseline, and have not yet been reported as visible via [withVisible].
 */
internal data class NewArticleTracking(
    val knownIds: Set<String>? = null,
    val unseenIds: Set<String> = emptySet(),
)

/**
 * Folds in a fresh raw query result. A call whose *previous* [NewArticleTracking.knownIds] was
 * `null` or empty only seeds the new baseline (nothing is "new" relative to a baseline that either
 * didn't exist yet, or held literally nothing the user could have missed — e.g. a brand-new feed
 * with no articles yet, or an empty folder/tag, right before its first real content lands). Every
 * other call adds whatever ids are in [ids] but weren't in the previous [NewArticleTracking.knownIds],
 * and drops any previously-unseen id that fell out of [ids] (deleted, unsubscribed, or filtered out
 * by the query itself — e.g. sync-merge propagating a tombstone).
 *
 * The empty-baseline case only actually fires once per burst of arrivals, because
 * [HomeViewModel.filteredArticles]'s underlying SQLDelight query coalesces every write inside one
 * DB transaction into a single re-emission — and every real writer of a whole batch of articles at
 * once (`FeedRepository.applyFetch`'s own fetch-then-insert, a sync merge) commits that way. Two
 * separate, non-transactional inserts would instead surface as two raw-query emissions: the first
 * re-seeds the baseline, but the second then diffs against *that* non-empty baseline and correctly
 * (if perhaps confusingly, outside a transaction) reports the second insert as new.
 */
internal fun NewArticleTracking.withList(ids: Set<String>): NewArticleTracking {
    val previouslyKnown = knownIds
    val nextUnseen = if (previouslyKnown.isNullOrEmpty()) {
        unseenIds
    } else {
        (unseenIds + (ids - previouslyKnown)).intersect(ids)
    }
    return copy(knownIds = ids, unseenIds = nextUnseen)
}

/**
 * Marks [ids] as already known without the user having scrolled to them — used for articles the
 * user just brought in themselves (a subscription), which were never "missed".
 *
 * A `null`/empty baseline is returned unchanged: the next raw query emission seeds the baseline
 * anyway (see [withList]), so nothing it contains can be counted. Otherwise [ids] join
 * [NewArticleTracking.knownIds] and leave [NewArticleTracking.unseenIds]. The result is the same
 * whichever side of this call the corresponding raw query emission lands on: before it, the ids are
 * in the baseline when the emission diffs and so are never added; after it, the emission already
 * counted them and this removes them again. Either way every *other* unseen id is kept, and an id
 * in [ids] that isn't under the current filter at all only widens [NewArticleTracking.knownIds]
 * harmlessly (the next [withList] replaces it with the actual result).
 */
internal fun NewArticleTracking.withAcknowledged(ids: Set<String>): NewArticleTracking {
    val known = knownIds
    if (known.isNullOrEmpty() || ids.isEmpty()) return this
    return copy(knownIds = known + ids, unseenIds = unseenIds - ids)
}

/** Drops [ids] from [NewArticleTracking.unseenIds] — the list's own report of what's now on screen. */
internal fun NewArticleTracking.withVisible(ids: Set<String>): NewArticleTracking =
    if (unseenIds.isEmpty()) this else copy(unseenIds = unseenIds - ids)

/** Clears every unseen id — the pill's own tap action. */
internal fun NewArticleTracking.allSeen(): NewArticleTracking =
    if (unseenIds.isEmpty()) this else copy(unseenIds = emptySet())
