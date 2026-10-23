package works.merc.keryx.app.presentation

import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.MILLIS_PER_HOUR
import works.merc.keryx.app.core.MILLIS_PER_MINUTE

/** Past this many days a notification's timestamp is shown as [RelativeTime.Absolute]. */
private const val DAYS_PER_WEEK = 7

/**
 * Which relative-time label a notification row shows, given how long ago it was raised. Shared so
 * both readers (Compose's `NotificationCenterSheet.kt`, the Apple app's `NotificationBell.swift`)
 * bucket the same timestamp into the same label.
 */
sealed interface RelativeTime {
    data object Now : RelativeTime
    data class Minutes(val count: Int) : RelativeTime
    data class Hours(val count: Int) : RelativeTime
    data class Days(val count: Int) : RelativeTime

    /** Older than a week: shown as an absolute date and time instead. */
    data object Absolute : RelativeTime
}

/**
 * Buckets [diffMillis] (now minus the notification's timestamp) into a [RelativeTime]. A negative
 * difference (a timestamp slightly ahead of the clock) counts as [RelativeTime.Now].
 */
fun relativeTimeOf(diffMillis: Long): RelativeTime = when {
    diffMillis < MILLIS_PER_MINUTE -> RelativeTime.Now
    diffMillis < MILLIS_PER_HOUR -> RelativeTime.Minutes((diffMillis / MILLIS_PER_MINUTE).toInt())
    diffMillis < MILLIS_PER_DAY -> RelativeTime.Hours((diffMillis / MILLIS_PER_HOUR).toInt())
    diffMillis < DAYS_PER_WEEK * MILLIS_PER_DAY -> RelativeTime.Days((diffMillis / MILLIS_PER_DAY).toInt())
    else -> RelativeTime.Absolute
}
