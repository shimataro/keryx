package works.merc.keryx.app.ui.home

/**
 * Tracks which newly inserted article ids have appeared in the current filter's raw query result
 * but have not yet scrolled into the article list's viewport — the "new articles" pill's own state
 * machine (see `ArticleListPane`/`NewArticlesPill`).
 *
 * Driven from the *raw* per-filter query result (`HomeViewModel.filteredArticles`), not the display
 * list (`HomeViewModel.articles`): the raw query is unaffected by the unread-only toggle or sort
 * direction, so toggling either never manufactures a false "new article". A filter change instead
 * replaces this whole instance (`HomeViewModel`'s `onStart { ... }` on the newly-selected filter's
 * flow), so an existing article under the new filter is never mistaken for new.
 *
 * An id counts as new only if it is both absent from [knownIds] *and* its row was inserted after
 * [insertedAfterRowId]. The id diff alone can't tell a genuinely new article from an existing one
 * that merely re-entered the filter's query — unstarred and re-starred while browsing Starred, its
 * feed moved into the folder being viewed, a tag attached to its feed, a star synced in from another
 * device — none of which the user "missed". The rowid check is what rules those out; see
 * `ArticleRepository.articleIdsInsertedAfter`.
 *
 * @param knownIds Every id seen in any raw query result since the baseline was seeded under this
 *   filter (a cumulative union, not just the latest result), plus any acknowledged via
 *   [withAcknowledged]. Cumulative so that an article which leaves the result and later comes back
 *   is never counted a second time. `null` until the first emission, and treated exactly like an
 *   empty one — see [withList] for why the two are the same thing as far as "what could the user
 *   have missed" goes.
 * @param unseenIds Newly inserted ids that appeared in an emission later than the one that
 *   established a non-empty [knownIds] baseline, and have not yet been reported as visible via
 *   [withVisible].
 * @param insertedAfterRowId The `articles` rowid watermark taken when this instance was created
 *   (just before the filter's query first ran) — a row with a larger rowid was inserted since.
 */
internal data class NewArticleTracking(
    val knownIds: Set<String>? = null,
    val unseenIds: Set<String> = emptySet(),
    val insertedAfterRowId: Long = 0,
)

/**
 * The ids in [ids] that [withList] could count as new, before the rowid check narrows them further:
 * those not yet in [NewArticleTracking.knownIds]. Empty while the baseline is `null`/empty, since
 * [withList] only seeds it then — so a caller can skip the rowid lookup entirely.
 */
internal fun NewArticleTracking.candidatesIn(ids: Set<String>): Set<String> {
    val known = knownIds
    return if (known.isNullOrEmpty()) emptySet() else ids - known
}

/**
 * Folds in a fresh raw query result [ids]. [inserted] holds the ids — looked up for this result's
 * [candidatesIn] — whose rows were inserted after [NewArticleTracking.insertedAfterRowId].
 *
 * A call whose *previous* [NewArticleTracking.knownIds] was `null` or empty only seeds the baseline
 * (nothing is "new" relative to a baseline that either didn't exist yet, or held literally nothing
 * the user could have missed — e.g. a brand-new feed with no articles yet, or an empty folder/tag,
 * right before its first real content lands). Every other call adds each id that is a candidate
 * *against the current state* and also in [inserted], widens [NewArticleTracking.knownIds] by
 * [ids], and drops any previously-unseen id that fell out of [ids] (deleted, unsubscribed, or
 * filtered out by the query itself — e.g. sync-merge propagating a tombstone). Recomputing the
 * candidates here, rather than trusting the ones [inserted] was looked up for, keeps a
 * [withAcknowledged] that landed during that lookup in effect.
 *
 * The empty-baseline case only actually fires once per burst of arrivals, because
 * [HomeViewModel.filteredArticles]'s underlying SQLDelight query coalesces every write inside one
 * DB transaction into a single re-emission — and every real writer of a whole batch of articles at
 * once (`FeedRepository.applyFetch`'s own fetch-then-insert, a sync merge) commits that way. Two
 * separate, non-transactional inserts would instead surface as two raw-query emissions: the first
 * seeds the baseline, but the second then diffs against *that* non-empty baseline and correctly
 * (if perhaps confusingly, outside a transaction) reports the second insert as new.
 */
internal fun NewArticleTracking.withList(ids: Set<String>, inserted: Set<String>): NewArticleTracking {
    val known = knownIds
    if (known.isNullOrEmpty()) return copy(knownIds = ids)
    val candidates = ids - known
    val newlyInserted = if (candidates.isEmpty() || inserted.isEmpty()) emptySet() else candidates.intersect(inserted)
    val nextUnseen = (unseenIds + newlyInserted).intersect(ids)
    return copy(knownIds = known + ids, unseenIds = nextUnseen)
}

/**
 * Marks [ids] as already known without the user having scrolled to them — used for articles the
 * user just brought in themselves (a subscription), which were never "missed".
 *
 * A `null`/empty baseline is returned unchanged: the next raw query emission seeds the baseline
 * anyway (see [withList]), so nothing it contains can be counted. Otherwise [ids] join
 * [NewArticleTracking.knownIds] and leave [NewArticleTracking.unseenIds]. The result is the same
 * whichever side of this call the corresponding raw query emission lands on: before it, the ids are
 * already known when the emission diffs and so are never added; after it, the emission already
 * counted them and this removes them again. Either way every *other* unseen id is kept. An id in
 * [ids] that isn't under the current filter at all stays in [NewArticleTracking.knownIds] for the
 * rest of this filter's lifetime, which is harmless: it is an existing article either way, so were
 * it ever to enter the filter later (its feed moved into the viewed folder, say) it shouldn't count
 * as new anyway.
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
