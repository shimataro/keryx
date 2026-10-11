#if os(iOS)
import SwiftUI

/// Settings ▸ About on iOS: the version and the support/legal links `AboutView` shows on macOS.
struct AboutSettingsPage: View {
    var body: some View {
        List {
            Section { Text(appVersionText) }
            Section { rows(AboutLinks.support) }
            Section { rows(AboutLinks.legal) }
        }
    }

    @ViewBuilder
    private func rows(_ links: [AboutLink]) -> some View {
        ForEach(links) { item in
            Link(item.label, destination: item.url)
        }
    }
}
#endif
