#if os(iOS)
import SwiftUI

/// Settings ▸ About on iOS: the version and the support/legal links `AboutView` shows on macOS.
struct AboutSettingsPage: View {
    var body: some View {
        List {
            Section { Text(appVersionText) }
            Section { ForEach(AboutLinks.support, content: row) }
            Section { ForEach(AboutLinks.legal, content: row) }
        }
    }

    /// A link row. Where macOS shows `tooltip` on hover, iOS has no hover, so it is shown under the
    /// label instead — otherwise the contact address would be nowhere on screen, and a device with
    /// no mail account set up cannot open the `mailto:` link to find it.
    private func row(_ item: AboutLink) -> some View {
        Link(destination: item.url) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.label)
                if let tooltip = item.tooltip {
                    Text(tooltip)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
#endif
