package works.merc.keryx.app.presentation

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
    diffMillis < 60_000L -> RelativeTime.Now
    diffMillis < 3_600_000L -> RelativeTime.Minutes((diffMillis / 60_000L).toInt())
    diffMillis < 86_400_000L -> RelativeTime.Hours((diffMillis / 3_600_000L).toInt())
    diffMillis < 604_800_000L -> RelativeTime.Days((diffMillis / 86_400_000L).toInt())
    else -> RelativeTime.Absolute
}
