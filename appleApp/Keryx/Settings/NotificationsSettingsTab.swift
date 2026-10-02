import KeryxShared
import SwiftUI

/// Only the app-level on/off switch: the OS permission request (`UNUserNotificationCenter`) is made
/// by `KeryxApp` whenever this setting is on, at startup and the moment it is switched on.
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
