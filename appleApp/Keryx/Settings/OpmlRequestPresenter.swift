import SwiftUI

/// Shows Settings on the Data tab whenever an OPML import/export is waiting there
/// (`OpmlTransferObservable.pendingRequest` — asked for by the File menu or an `.opml` file the app
/// was opened with), so `DataSettingsTab` can carry it out. Applied to Home only, so a file opened
/// during Setup waits until Setup is done — matching Compose, whose single Settings router never opens
/// over Setup (`SettingsOpenRequests`). Waits while another operation runs, like Compose's `App.kt`.
struct OpmlRequestPresenter: ViewModifier {
    let opmlTransfer: OpmlTransferObservable
    let settingsNavigation: SettingsNavigation

    #if os(macOS)
    @Environment(\.openSettings) private var openSettings
    #endif

    private var shouldPresent: Bool {
        opmlTransfer.pendingRequest != nil && !opmlTransfer.isBusy
    }

    func body(content: Content) -> some View {
        content.onChange(of: shouldPresent, initial: true) { _, present in
            guard present else { return }
            settingsNavigation.show(tabId: SettingsNavigation.Tab.data)
            #if os(macOS)
            openSettings()
            #else
            settingsNavigation.isSheetPresented = true
            #endif
        }
    }
}

