package works.merc.keryx.app.presentation.home

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
