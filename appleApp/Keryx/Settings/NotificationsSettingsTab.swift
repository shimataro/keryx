import KeryxShared
import SwiftUI

/// OS notification permission request (`UNUserNotificationCenter`) is wired in M5 alongside the
/// rest of the OS-notification plumbing; this tab only exposes the app-level on/off switch for now.
struct NotificationsSettingsTab: View {
    let preferences: PreferencesObservable

    var body: some View {
        Form {
            Toggle(L("settings_notification_enabled"), isOn: enabledBinding(preferences.notificationEnabled))
        }
        .settingsFormPadding()
    }

    private func enabledBinding(_ enabled: Bool) -> Binding<Bool> {
        Binding(
            get: { enabled },
            set: { preferences.controller.setNotificationEnabled(enabled: $0) }
        )
    }
}
