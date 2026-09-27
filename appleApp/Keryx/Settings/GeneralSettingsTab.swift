import KeryxShared
import SwiftUI

struct GeneralSettingsTab: View {
    let preferences: PreferencesObservable

    var body: some View {
        Form {
            if let settings = preferences.localSettings {
                Picker(L("settings_theme"), selection: themeBinding(settings)) {
                    Text(L("settings_theme_system")).tag("system")
                    Text(L("settings_theme_light")).tag("light")
                    Text(L("settings_theme_dark")).tag("dark")
                }
                .pickerStyle(.segmented)

                Picker(L("settings_font_size"), selection: fontScaleBinding(settings)) {
                    Text(L("settings_font_small")).tag(0.85)
                    Text(L("settings_font_medium")).tag(1.0)
                    Text(L("settings_font_large")).tag(1.2)
                    Text(L("settings_font_xlarge")).tag(1.4)
                }
                .pickerStyle(.segmented)

                Picker(L("settings_refresh_interval"), selection: refreshIntervalBinding(settings)) {
                    Text(L("settings_refresh_min15")).tag(Int32(15))
                    Text(L("settings_refresh_min30")).tag(Int32(30))
                    Text(L("settings_refresh_hour1")).tag(Int32(60))
                    Text(L("settings_refresh_hour3")).tag(Int32(180))
                    Text(L("settings_refresh_manual")).tag(Int32(0))
                }
                .pickerStyle(.segmented)

                Toggle(L("settings_start_minimized"), isOn: startMinimizedBinding(settings))
            } else {
                ProgressView()
            }
        }
        .padding()
    }

    private func themeBinding(_ settings: LocalSettings) -> Binding<String> {
        Binding(get: { settings.themeMode }, set: { preferences.controller.setThemeMode(mode: $0) })
    }

    private func fontScaleBinding(_ settings: LocalSettings) -> Binding<Double> {
        Binding(get: { settings.fontSizeScale }, set: { preferences.controller.setFontScale(scale: $0) })
    }

    private func refreshIntervalBinding(_ settings: LocalSettings) -> Binding<Int32> {
        Binding(
            get: { settings.refreshIntervalMinutes },
            set: { preferences.controller.setRefreshIntervalMinutes(minutes: $0) }
        )
    }

    private func startMinimizedBinding(_ settings: LocalSettings) -> Binding<Bool> {
        Binding(get: { settings.startMinimized }, set: { preferences.controller.setStartMinimized(enabled: $0) })
    }
}
