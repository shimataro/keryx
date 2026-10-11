import SwiftUI

/// One row of the About links.
struct AboutLink: Identifiable {
    let label: String
    let url: URL
    /// Shown as the macOS hover tooltip; the destination unless given (the contact row shows the
    /// bare address, not the `mailto:` URL, like Compose's `EmailLinkRow`).
    let tooltip: String?

    var id: String { url.absoluteString }
}

/// The About links shared by the macOS About window and the iOS Settings ▸ About page — one list so
/// the two platforms cannot drift (and the privacy policy is reachable in the iOS app, as App
/// Review guideline 5.1.1(i) requires). Grouped and ordered like Compose's AboutDialog.kt: support
/// links first, then the legal documents.
enum AboutLinks {
    static var support: [AboutLink] {
        [
            AboutLink(label: L("settings_website"), url: URL(string: L("website_url"))!, tooltip: nil),
            AboutLink(label: L("settings_project_page"), url: URL(string: projectUrl)!, tooltip: nil),
            AboutLink(
                label: L("settings_contact"), url: URL(string: "mailto:\(L("contact_email"))")!,
                tooltip: L("contact_email")
            ),
        ]
    }

    static var legal: [AboutLink] {
        [
            AboutLink(label: L("settings_terms"), url: URL(string: L("terms_url"))!, tooltip: nil),
            AboutLink(label: L("settings_privacy_policy"), url: URL(string: L("privacy_policy_url"))!, tooltip: nil),
            AboutLink(label: L("settings_licenses"), url: URL(string: licensesUrl)!, tooltip: nil),
        ]
    }
}

/// "Version x.y.z", from the bundle.
var appVersionText: String {
    LF("settings_version", Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
}
