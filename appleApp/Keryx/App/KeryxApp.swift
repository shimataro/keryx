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
                if let sdk = model.sdk, let home = model.home, let preferences = model.preferences {
                    if model.needsSetup {
                        SetupView(
                            controller: sdk.setupController,
                            oauthCoordinator: model.oauthCoordinator,
                            onDone: { model.completeSetup() }
                        )
                    } else if let notifications = model.notifications {
                        HomeView(home: home, sidebarDialogs: model.sidebarDialogs, notifications: notifications, preferences: preferences)
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
            // Applies the in-app theme setting to every SwiftUI-rendered surface — Settings/About
            // scenes read it independently through their own environment inheritance. Started here
            // (not only inside `SettingsView`'s own `.task`) so it takes effect before Settings is
            // ever opened. `NSApp.appearance` additionally covers the surfaces `preferredColorScheme`
            // does not reach: native menus, and any AppKit chrome outside this scene's own view tree.
            .task { await model.preferences?.startObserving() }
            .preferredColorScheme(colorScheme(for: model.preferences?.localSettings?.themeMode))
            #if os(macOS)
            .onChange(of: model.preferences?.localSettings?.themeMode, initial: true) { _, mode in
                applyAppearance(mode)
            }
            #endif
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

    /// Mirrors `resolveDarkTheme`'s `"light"`/`"dark"`/else (follow system) rule (`KeryxTheme.kt`),
    /// applied to native (non-SwiftUI) surfaces `.preferredColorScheme` does not reach.
    private func applyAppearance(_ mode: String?) {
        switch mode {
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        default: NSApp.appearance = nil
        }
    }
    #endif

    /// Mirrors `resolveDarkTheme`'s same rule for SwiftUI's own `.preferredColorScheme` — `nil`
    /// leaves the system setting in charge.
    private func colorScheme(for mode: String?) -> ColorScheme? {
        switch mode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}
