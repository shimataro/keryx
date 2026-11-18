package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.isAlert

/**
 * The most severe level among [items] (`ERROR` > `WARNING` > `INFO`), or `null` when there are none.
 * The order is spelled out here rather than taken from the enum's declaration order, so reordering or
 * extending [AppNotificationLevel] cannot silently change the result.
 *
 * Both UIs name this level to assistive technology on the notification center's entry point, so the
 * severity its bell's tint shows is not conveyed by color alone.
 */
fun highestNotificationLevel(items: List<AppNotification>): AppNotificationLevel? =
    items.maxByOrNull { severityRank(it.level) }?.level

private fun severityRank(level: AppNotificationLevel): Int = when (level) {
    AppNotificationLevel.INFO -> 0
    AppNotificationLevel.WARNING -> 1
    AppNotificationLevel.ERROR -> 2
}

/** The tone the notification center's bell takes — see [notificationBellTone]. */
enum class NotificationBellTone {
    /** Nothing in the notification center: the row's own content color. */
    QUIET,

    /** Notices only (`INFO`): the accent/primary color. */
    NOTICE,

    /** At least one alert (`WARNING`/`ERROR`, see [isAlert]): the error color. */
    ALERT,
}

/**
 * The bell's tone for [items]. Deciding which levels share a tone is common to both UIs (Compose's
 * `NotificationCenterRow.kt`, SwiftUI's `NotificationCenterRow.swift`), so it lives here and each UI
 * only maps the tone to its own colors.
 */
fun notificationBellTone(items: List<AppNotification>): NotificationBellTone = when {
    items.isEmpty() -> NotificationBellTone.QUIET
    items.any { it.level.isAlert } -> NotificationBellTone.ALERT
    else -> NotificationBellTone.NOTICE
}
