import SwiftUI

@main
struct KeryxApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if let sdk = model.sdk, let home = model.home {
                    if model.needsSetup {
                        SetupView(
                            controller: sdk.setupController,
                            oauthCoordinator: model.oauthCoordinator,
                            onDone: { model.completeSetup() }
                        )
                    } else {
                        HomeView(home: home)
                    }
                } else {
                    StartupErrorView(error: model.startupError)
                }
            }
        }

        #if os(macOS)
        // The `Settings` scene (Cmd+, / the app menu's "Settings…") is a macOS-only concept — iOS
        // has no equivalent scene type. Reaching Settings on iOS is deferred to whenever iOS's own
        // UI gets built out; for now this scene simply doesn't exist there.
        Settings {
            if let sdk = model.sdk, let preferences = model.preferences, let cloudSync = model.cloudSync {
                SettingsView(sdk: sdk, preferences: preferences, cloudSync: cloudSync, oauthCoordinator: model.oauthCoordinator)
            }
        }
        #endif
    }
}
