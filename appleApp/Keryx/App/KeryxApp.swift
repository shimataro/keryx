import SwiftUI
#if os(macOS)
import AppKit
#endif

@main
struct KeryxApp: App {
    @State private var model = AppModel()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif

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
                    } else if let notifications = model.notifications {
                        HomeView(home: home, sidebarDialogs: model.sidebarDialogs, notifications: notifications)
                            .onOpenURL { url in
                                if url.pathExtension.lowercased() == "opml" {
                                    model.importOpenedOpml(url: url)
                                }
                            }
                            #if os(macOS)
                            .onChange(of: home.totalUnread, initial: true) { _, count in
                                updateDockBadge(count)
                            }
                            #endif
                    }
                } else {
                    StartupErrorView(error: model.startupError)
                }
            }
        }
        .commands { HomeCommands(model: model) }

        #if os(macOS)
        // The `Settings` scene (Cmd+, / the app menu's "Settings…") is a macOS-only concept — iOS
        // has no equivalent scene type. Reaching Settings on iOS is deferred to whenever iOS's own
        // UI gets built out; for now this scene simply doesn't exist there.
        Settings {
            if let sdk = model.sdk, let preferences = model.preferences, let cloudSync = model.cloudSync {
                SettingsView(sdk: sdk, preferences: preferences, cloudSync: cloudSync, oauthCoordinator: model.oauthCoordinator)
            }
        }

        Window(L("menu_help_about"), id: "about") {
            AboutView()
        }
        .windowResizability(.contentSize)

        MenuBarExtra {
            Button(L("tray_show")) {
                NSApp.setActivationPolicy(.regular)
                NSApp.activate(ignoringOtherApps: true)
                for window in NSApp.windows { window.makeKeyAndOrderFront(nil) }
            }
            Button(L("tray_hide")) {
                for window in NSApp.windows { window.orderOut(nil) }
                NSApp.setActivationPolicy(.accessory)
            }
            Divider()
            Button(L("tray_quit")) { NSApp.terminate(nil) }
        } label: {
            Image(systemName: (model.home?.totalUnread ?? 0) > 0 ? "envelope.badge.fill" : "envelope")
        }
        #endif
    }

    #if os(macOS)
    private func updateDockBadge(_ count: Int64) {
        NSApp.dockTile.badgeLabel = count <= 0 ? nil : (count > 99 ? "99+" : "\(count)")
    }
    #endif
}
