package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.ArticleFlags
import works.merc.keryx.app.domain.ArticleListRow

/**
 * Whether [current] is a pin written after [snapshot] was taken — added (no [snapshot]) or replaced
 * (a different instance, even with an equal value).
 *
 * Compared by identity, not equality: every write that pins a value creates a fresh instance, so a
 * re-pin with the same value is still a write a pass working from [snapshot] knows nothing about.
 * [HomeViewModel]'s reconcile judges a pin by this rule; its read-state re-trim has a rule of its
 * own ([retrimmedSelectionPin]), which keeps any existing pin whether replaced or not.
 */
internal fun <T : Any> pinReplacedSince(snapshot: T?, current: T): Boolean = snapshot == null || current !== snapshot

/**
 * The selection's read-state pin a re-trim keeps, decided against the map it updates.
 *
 * @param prior The selection's pin in the map the re-trim started from, if any.
 * @param now The selection's pin in the map being updated, if any — a concurrent reconcile pass may
 *   have refreshed or dropped [prior] since.
 * @param candidate The pin the re-trim would add for the selection if it has none.
 * @return The pin to keep, or null to leave the selection unpinned.
 */
internal fun <T : Any> retrimmedSelectionPin(prior: T?, now: T?, candidate: T): T? =
    when {
        // An existing pin always stands, whether replaced since (a reconcile refresh, or a user
        // write) or unchanged: [candidate] is built from the selection, which a reconcile pass
        // refreshes only after the pin, so it can be the staler of the two — and every write that
        // changes the selection's read state re-pins it at the same time anyway.
        now != null -> now
        // Dropped since (an external change or a tombstone): it stays dropped.
        prior != null -> null
        else -> candidate
    }

/**
 * The read-state pins a reconcile pass keeps, revalidated against the DB's current flags.
 *
 * @param pinned The read-state pins as they are now (the map being updated).
 * @param snapshot The read-state pins as they were when [flags] was requested.
 * @param flags The current read/star flags of the alive articles among the pinned ids; an id missing
 *   from it is tombstoned (or gone).
 * @param selectedId The article the user has selected, if any.
 * @return [pinned] minus every pin the DB no longer agrees with, with the selection's own pin
 *   refreshed to the current value instead of dropped while its article is alive.
 */
internal fun reconciledReadPins(
    pinned: Map<String, ArticleListRow>,
    snapshot: Map<String, ArticleListRow>,
    flags: Map<String, ArticleFlags>,
    selectedId: String?,
): Map<String, ArticleListRow> =
    // A pin added or replaced since the snapshot is kept: `flags` was read before the write
    // that justified it was enqueued, so it has no verdict on it (identity, not equality — a
    // re-pin with the same value is still a write `flags` knows nothing about). Otherwise a
    // pin is dropped once the article's current is_read no longer matches the pinned value
    // (an external "mark unread"/"mark read", or a soft-delete tombstone), the same rule as
    // the starred pin ([reconciledUnstarredPins]) — except the selection's own pin, which while its article is
    // alive is refreshed to the current value instead: under unread-only a dropped unread
    // pin read externally would take the open article out of the list (and the pager).
    buildMap {
        pinned.forEach { (id, pin) ->
            val old = snapshot[id]
            val current = flags[id]
            when {
                pinReplacedSince(old, pin) || current?.isRead == old?.is_read -> put(id, pin)
                id == selectedId && current != null -> put(id, pin.copy(is_read = current.isRead))
            }
        }
    }

/**
 * The unstarred pins a reconcile pass keeps, revalidated against the DB's current flags.
 *
 * @param pinned The unstarred pins as they are now (the map being updated).
 * @param snapshot The unstarred pins as they were when [flags] was requested.
 * @param flags The current read/star flags of the alive articles among the pinned ids; an id missing
 *   from it is tombstoned (or gone).
 * @return [pinned] minus every pin in [snapshot] whose article's current is_starred no longer matches
 *   the pinned value; pins added since [snapshot] are kept.
 */
internal fun reconciledUnstarredPins(
    pinned: Map<String, ArticleListRow>,
    snapshot: Map<String, ArticleListRow>,
    flags: Map<String, ArticleFlags>,
): Map<String, ArticleListRow> =
    // Same idea as the read pin: dropped once the article's current is_starred no
    // longer matches what was optimistically pinned (deleted, or externally re-starred),
    // not just once it is deleted.
    pinned.filterKeys { it !in snapshot || flags[it]?.isStarred == pinned.getValue(it).is_starred }
