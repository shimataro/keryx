import KeryxShared
import SwiftUI

struct GeneralSettingsTab: View {
    let preferences: PreferencesObservable

    var body: some View {
        Form {
            Picker(L("settings_theme"), selection: themeBinding(preferences.themeMode)) {
                Text(L("settings_theme_system")).tag("system")
                Text(L("settings_theme_light")).tag("light")
                Text(L("settings_theme_dark")).tag("dark")
            }
            .pickerStyle(.segmented)

            Picker(L("settings_font_size"), selection: fontScaleBinding(preferences.fontSizeScale)) {
                Text(L("settings_font_small")).tag(0.85)
                Text(L("settings_font_medium")).tag(1.0)
                Text(L("settings_font_large")).tag(1.2)
                Text(L("settings_font_xlarge")).tag(1.4)
            }
            .pickerStyle(.menu)

            Picker(L("settings_refresh_interval"), selection: refreshIntervalBinding(preferences.refreshIntervalMinutes)) {
                Text(L("settings_refresh_min15")).tag(Int32(15))
                Text(L("settings_refresh_min30")).tag(Int32(30))
                Text(L("settings_refresh_hour1")).tag(Int32(60))
                Text(L("settings_refresh_hour3")).tag(Int32(180))
                Text(L("settings_refresh_manual")).tag(Int32(0))
            }
            .pickerStyle(.menu)

            // Only macOS has a menu bar status item to start hidden into (`AppDelegate`); an iOS
            // app cannot launch hidden at all — the same gate as Compose's own `hasSystemTray`.
            #if os(macOS)
            Toggle(L("settings_start_minimized"), isOn: startMinimizedBinding(preferences.startMinimized))
            #endif
        }
        .settingsFormPadding()
    }

    // Each takes its own mirrored field, read in `body`, never one through
    // `preferences.localSettings` — see `PreferencesObservable`.
    private func themeBinding(_ value: String) -> Binding<String> {
        Binding(get: { value }, set: { preferences.controller.setThemeMode(mode: $0) })
    }

    private func fontScaleBinding(_ value: Double) -> Binding<Double> {
        Binding(get: { value }, set: { preferences.controller.setFontScale(scale: $0) })
    }

    private func refreshIntervalBinding(_ value: Int32) -> Binding<Int32> {
        Binding(
            get: { value },
            set: { preferences.controller.setRefreshIntervalMinutes(minutes: $0) }
        )
    }

    private func startMinimizedBinding(_ value: Bool) -> Binding<Bool> {
        Binding(get: { value }, set: { preferences.controller.setStartMinimized(enabled: $0) })
    }
}
