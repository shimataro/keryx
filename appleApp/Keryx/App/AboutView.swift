#if os(macOS)
import SwiftUI

/// "About Keryx" — reached from the app menu (`CommandGroup(replacing: .appInfo)`). A `Window`
/// scene (see `KeryxApp`) rather than an `NSAlert`/panel, so it's a plain SwiftUI view like
/// everything else here. macOS-only for now — there is no equivalent entry point on iOS yet.
struct AboutView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
            Text(L("app_name")).font(.title.bold())
            Text(LF("settings_version", appVersion))
                .foregroundStyle(.secondary)

            VStack(spacing: 4) {
                Link(L("settings_website"), destination: URL(string: L("website_url"))!)
                Link(L("settings_project_page"), destination: URL(string: projectUrl)!)
                Link(L("settings_licenses"), destination: URL(string: licensesUrl)!)
                Link(L("settings_privacy_policy"), destination: URL(string: L("privacy_policy_url"))!)
                Link(L("settings_terms"), destination: URL(string: L("terms_url"))!)
            }
        }
        .padding(32)
        .frame(width: 360)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }
}
#endif
