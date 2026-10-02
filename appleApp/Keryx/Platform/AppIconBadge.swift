import UserNotifications

/// The app icon badge number for an unread count: never negative, and clamped to `Int`'s range.
func appIconBadgeCount(unreadCount: Int64) -> Int {
    Int(clamping: max(unreadCount, 0))
}

/// Shows the total unread count on the app icon (needs the notification authorization's `.badge`
/// option, requested with the rest — see `OsNotificationPoster.requestAuthorization`).
func updateAppIconBadge(unreadCount: Int64) {
    UNUserNotificationCenter.current().setBadgeCount(appIconBadgeCount(unreadCount: unreadCount)) { _ in }
}
