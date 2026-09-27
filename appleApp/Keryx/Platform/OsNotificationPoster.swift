import UserNotifications

/// Posts the new-articles OS notification via `UNUserNotificationCenter` — wired as `KeryxSdk.start`'s
/// `postOsNotification` closure. Whether to notify at all (the `notificationEnabled` setting, and
/// the new-article count actually being `> 0`) is already decided on the Kotlin side
/// (`NewArticleNotifier.notifyIfEnabled`) before this is ever called, so this only requests
/// authorization (once, best-effort) and posts.
enum OsNotificationPoster {
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func post(message: String) {
        let content = UNMutableNotificationContent()
        content.title = L("app_name")
        content.body = message
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
