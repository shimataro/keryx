package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.RefreshOutcome

/**
 * What the feed refreshes the user started themselves — a pull, Refresh All, a feed's own Refresh —
 * are doing: still checking, or finished with an outcome. Automatic refreshes (startup, background)
 * never appear here, so a UI showing it moves nothing the user did not ask for. The iOS toolbar's
 * status area shows it (`external-spec.md` §9).
 *
 * Overlapping refreshes count as one check: it stays [Checking] until the last of them has finished,
 * and then reports their outcomes combined ([RefreshOutcome.plus]).
 */
sealed interface ManualRefreshStatus {
    /** None has run yet, or the last ones refreshed no feed at all. */
    data object Idle : ManualRefreshStatus

    /** [running] refreshes are in flight; [pending] combines those that have already finished. */
    data class Checking(val running: Int, val pending: RefreshOutcome?) : ManualRefreshStatus

    /**
     * Every refresh has finished; [outcome] is how they went. Stays until the next one starts.
     * [generation] is unique to each end and increases with it, so two checks in a row with the same
     * outcome stay unequal and a StateFlow collector that missed the [Checking] in between still
     * receives the second one rather than having it conflated away.
     */
    data class Finished(val outcome: RefreshOutcome, val generation: Long) : ManualRefreshStatus
}

/** The status once one more manual refresh has started. */
internal fun ManualRefreshStatus.started(): ManualRefreshStatus = when (this) {
    is ManualRefreshStatus.Checking -> copy(running = running + 1)
    else -> ManualRefreshStatus.Checking(running = 1, pending = null)
}

/**
 * The status once one manual refresh has ended with [outcome] (`null` when it refreshed no feed),
 * stamping [generation] on a resulting [ManualRefreshStatus.Finished]. An end with nothing running is
 * ignored.
 */
internal fun ManualRefreshStatus.ended(outcome: RefreshOutcome?, generation: Long): ManualRefreshStatus {
    if (this !is ManualRefreshStatus.Checking) return this
    val combined = when {
        pending == null -> outcome
        outcome == null -> pending
        else -> pending + outcome
    }
    return when {
        running > 1 -> ManualRefreshStatus.Checking(running - 1, combined)
        combined != null -> ManualRefreshStatus.Finished(combined, generation)
        else -> ManualRefreshStatus.Idle
    }
}
