package works.merc.keryx.app.domain

/**
 * The schedule a periodic background-refresh mechanism (Android's `WorkManager`) should run at,
 * derived from the user's refresh-interval setting. Kept as a pure mapping in commonMain — rather
 * than inline in `androidMain`'s WorkManager wiring — so it's testable without an
 * `androidUnitTest` source set (this module has none).
 */
sealed interface BackgroundRefreshSchedule {
    /** "Manual only" (`refreshIntervalMinutes <= 0`) — no periodic work should run at all. */
    data object Disabled : BackgroundRefreshSchedule

    data class Periodic(val minutes: Long) : BackgroundRefreshSchedule
}

/**
 * Maps a `refreshIntervalMinutes` setting to a [BackgroundRefreshSchedule].
 *
 * @param minimumMinutes The shortest interval the underlying scheduler can actually honor
 *   (`WorkManager`'s `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes). The app's
 *   own UI never offers a positive value below this, but a positive value under the floor —
 *   e.g. from a hand-edited or migrated `local_settings.json` — is coerced up to it rather than
 *   silently disabled, since a positive setting means "I want this to run periodically".
 */
fun backgroundRefreshSchedule(refreshIntervalMinutes: Int, minimumMinutes: Long = 15): BackgroundRefreshSchedule =
    if (refreshIntervalMinutes <= 0) {
        BackgroundRefreshSchedule.Disabled
    } else {
        BackgroundRefreshSchedule.Periodic(refreshIntervalMinutes.toLong().coerceAtLeast(minimumMinutes))
    }
