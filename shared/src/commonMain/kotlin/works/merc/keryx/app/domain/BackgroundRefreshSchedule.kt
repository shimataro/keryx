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
 * @param minimumMinutes The shortest interval the underlying scheduler can actually honor. No
 *   default here on purpose — the real value (`WorkManager`'s own
 *   `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`, 15 minutes) is an Android-specific
 *   scheduler constant, and the only production caller (`background/BackgroundRefresh.kt`) passes
 *   it explicitly so this stays the single source of truth rather than a commonMain literal that
 *   could silently drift from it. The app's own UI never offers a positive value below this, but a
 *   positive value under the floor — e.g. from a hand-edited or migrated `local_settings.json` —
 *   is coerced up to it rather than silently disabled, since a positive setting means "I want this
 *   to run periodically".
 */
fun backgroundRefreshSchedule(refreshIntervalMinutes: Int, minimumMinutes: Long): BackgroundRefreshSchedule =
    if (refreshIntervalMinutes <= 0) {
        BackgroundRefreshSchedule.Disabled
    } else {
        BackgroundRefreshSchedule.Periodic(refreshIntervalMinutes.toLong().coerceAtLeast(minimumMinutes))
    }
