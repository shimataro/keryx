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

            // Order, grouping and spacing match Compose's own AboutDialog.kt: a divider before each
            // group — support links (website, project page, contact) first, then the legal
            // documents (terms, privacy, licenses) — with each link's destination as its tooltip.
            // The first divider takes only bottom padding: this stack's own spacing is its top.
            Divider().padding(.bottom, 12)
            VStack(spacing: 12) {
                link(L("settings_website"), url: L("website_url"))
                link(L("settings_project_page"), url: projectUrl)
                link(L("settings_contact"), url: "mailto:\(L("contact_email"))", tooltip: L("contact_email"))
            }
            Divider().padding(.vertical, 12)
            VStack(spacing: 12) {
                link(L("settings_terms"), url: L("terms_url"))
                link(L("settings_privacy_policy"), url: L("privacy_policy_url"))
                link(L("settings_licenses"), url: licensesUrl)
            }
        }
        .padding(32)
        .frame(width: 360)
    }

    /// A link row whose hover tooltip shows `tooltip` — its destination unless given (the contact
    /// row shows the bare address, not the `mailto:` URL, like Compose's `EmailLinkRow`).
    private func link(_ label: String, url: String, tooltip: String? = nil) -> some View {
        Link(label, destination: URL(string: url)!)
            .help(tooltip ?? url)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }
}
#endif
