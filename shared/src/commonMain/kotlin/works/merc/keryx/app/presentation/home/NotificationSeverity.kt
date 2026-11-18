package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel

/**
 * The most severe level among [items] (`ERROR` > `WARNING` > `INFO`), or `null` when there are none.
 *
 * The notification center's entry point (Compose's `NotificationCenterRow`, SwiftUI's
 * `NotificationCenterRow.swift`) tints its bell by this, so a warning or an error reads differently
 * from a new-version notice at a glance. Both UIs read this one function, and each only maps the
 * result to its own colors.
 */
fun highestNotificationLevel(items: List<AppNotification>): AppNotificationLevel? =
    items.maxOfOrNull { it.level }
