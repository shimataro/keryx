import Foundation

/// The Dock badge text for an unread count: `nil` clears the badge, otherwise the exact count.
/// Unlike the Compose desktop badge (`unreadBadgeLabel`), which must fit a hand-drawn badge and
/// caps at "99+", the native Dock badge sizes itself, so the count is never rounded.
func dockBadgeLabel(unreadCount: Int64) -> String? {
    unreadCount <= 0 ? nil : String(unreadCount)
}
