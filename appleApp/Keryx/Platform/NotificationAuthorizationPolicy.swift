/// Whether the app should ask the OS for notification permission right now.
///
/// Asked only once the setting is on *and* first-run Setup is over, so the system prompt never
/// appears over the Setup screen on first launch (the setting defaults to on) — it comes in context,
/// when the main window shows, or the moment the user switches the setting on. `enabled` is `nil`
/// while the preferences have not loaded yet. Kept free of UserNotifications so the standalone
/// `KeryxTests` bundle can compile it directly.
func shouldRequestNotificationAuthorization(enabled: Bool?, needsSetup: Bool) -> Bool {
    enabled == true && !needsSetup
}
