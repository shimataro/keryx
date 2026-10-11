#if os(macOS)
import SwiftUI

/// "About Keryx" — reached from the app menu (`CommandGroup(replacing: .appInfo)`). A `Window`
/// scene (see `KeryxApp`) rather than an `NSAlert`/panel, so it's a plain SwiftUI view like
/// everything else here. macOS only: iOS shows the same links as Settings ▸ About
/// (`AboutSettingsPage`).
struct AboutView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
            Text(L("app_name")).font(.title.bold())
            Text(appVersionText)
                .foregroundStyle(.secondary)

            // A divider before each group; the first takes only bottom padding because this
            // stack's own spacing is its top.
            Divider().padding(.bottom, 12)
            linkGroup(AboutLinks.support)
            Divider().padding(.vertical, 12)
            linkGroup(AboutLinks.legal)
        }
        .padding(32)
        .frame(width: 360)
    }

    private func linkGroup(_ links: [AboutLink]) -> some View {
        VStack(spacing: 12) {
            ForEach(links) { item in
                Link(item.label, destination: item.url)
                    .help(item.tooltip ?? item.url.absoluteString)
                    .linkPointer()
            }
        }
    }
}
#endif
