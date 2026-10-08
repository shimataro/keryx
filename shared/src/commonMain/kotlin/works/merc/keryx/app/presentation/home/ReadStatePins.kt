package works.merc.keryx.app.presentation.home

/**
 * Whether [current] is a pin written after [snapshot] was taken — added (no [snapshot]) or replaced
 * (a different instance, even with an equal value).
 *
 * Compared by identity, not equality: every write that pins a value creates a fresh instance, so a
 * re-pin with the same value is still a write a pass working from [snapshot] knows nothing about.
 * [HomeViewModel]'s reconcile and its read-state re-trim both judge a pin by this rule.
 */
internal fun <T : Any> pinReplacedSince(snapshot: T?, current: T): Boolean = snapshot == null || current !== snapshot

/**
 * The selection's read-state pin a re-trim keeps, decided against the map it updates.
 *
 * @param prior The selection's pin in the map the re-trim started from, if any.
 * @param now The selection's pin in the map being updated, if any — a concurrent reconcile pass may
 *   have refreshed or dropped [prior] since.
 * @param candidate The pin the re-trim would keep for the selection.
 * @param sameValue Whether two pins hold the same read state.
 * @return The pin to keep, or null to leave the selection unpinned.
 */
internal fun <T : Any> retrimmedSelectionPin(prior: T?, now: T?, candidate: T, sameValue: (T, T) -> Boolean): T? =
    when {
        now == null && prior == null -> candidate
        // Dropped since (an external change or a tombstone): it stays dropped.
        now == null -> null
        // Replaced since (a reconcile refresh, or a user write): its value stands.
        pinReplacedSince(prior, now) -> now
        else -> if (sameValue(now, candidate)) now else candidate
    }
