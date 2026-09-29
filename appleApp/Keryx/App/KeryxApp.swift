import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
#endif

@main
struct KeryxApp: App {
    @State private var model = AppModel()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some Scene {
        #if os(macOS)
        // `AppDelegate` has no access to the SDK or to SwiftUI's environment on its own — handing
        // it both here runs before any of its own launch callbacks fire, since `body` must be
        // evaluated to build the scene tree that starts the run loop.
        let _ = configureAppDelegate()
        #endif

        #if os(macOS)
        // A single-instance `Window`, not a `WindowGroup`: `AppDelegate` brings the main window
        // back through `openWindow(id: "main")` when it has no window to reveal, and on a
        // `WindowGroup` that always creates *another* window instead of reusing the existing one.
        Window(L("app_name"), id: "main") {
            mainContent
                .background(MainWindowReader { appDelegate.registerMainWindow($0) })
        }
        // Matches the desktop app's first-launch window (`WINDOW_DEFAULT_WIDTH` x `WINDOW_DEFAULT_HEIGHT`)
        // so the three panes get room at their ideal widths instead of the content-fitted minimum.
        .defaultSize(
            width: CGFloat(ConstantsKt.WINDOW_DEFAULT_WIDTH),
            height: CGFloat(ConstantsKt.WINDOW_DEFAULT_HEIGHT)
        )
        .commands { HomeCommands(model: model) }
        #else
        WindowGroup(id: "main") {
            mainContent
        }
        .commands { HomeCommands(model: model) }
        #endif

        #if os(macOS)
        // The `Settings` scene (Cmd+, / the app menu's "Settings…") is a macOS-only concept — iOS
        // has no equivalent scene type. Reaching Settings on iOS is deferred to whenever iOS's own
        // UI gets built out; for now this scene simply doesn't exist there.
        Settings {
            if let sdk = model.sdk, let preferences = model.preferences, let cloudSync = model.cloudSync,
               let opmlTransfer = model.opmlTransfer, let notifications = model.notifications {
                SettingsView(
                    sdk: sdk, preferences: preferences, cloudSync: cloudSync,
                    oauthCoordinator: model.oauthCoordinator, opmlTransfer: opmlTransfer,
                    notifications: notifications
                )
            }
        }

        Window(L("menu_help_about"), id: "about") {
            AboutView()
        }
        .windowResizability(.contentSize)
        #endif
    }

    private var mainContent: some View {
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
                        #if os(macOS)
                        .onChange(of: home.totalUnread, initial: true) { _, count in
                            updateDockBadge(count)
                            appDelegate.updateStatusItemAppearance(unreadCount: count)
                        }
                        #endif
                }
            } else {
                StartupErrorView(error: model.startupError)
            }
        }
        // Handled at this level (not nested inside the Home-only branch above) so a document
        // opened during Setup — or before `sdk` even finishes starting — isn't silently dropped.
        .onOpenURL { url in
            guard url.pathExtension.lowercased() == "opml" else { return }
            model.importOpenedOpml(url: url)
            #if os(macOS)
            appDelegate.revealMainWindow()
            #endif
        }
        // Applies the in-app theme setting to every SwiftUI-rendered surface — Settings/About
        // scenes read it independently through their own environment inheritance. Started here
        // (not only inside `SettingsView`'s own `.task`) so it takes effect before Settings is
        // ever opened. `NSApp.appearance` additionally covers the surfaces `preferredColorScheme`
        // does not reach: native menus, and any AppKit chrome outside this scene's own view tree.
        .task { await model.preferences?.startObserving() }
        .preferredColorScheme(colorScheme(for: model.preferences?.localSettings.themeMode))
        #if os(macOS)
        .onChange(of: model.preferences?.localSettings.themeMode, initial: true) { _, mode in
            applyAppearance(mode)
        }
        // Requested at startup (if already on) and the moment it's switched on — never
        // unconditionally at every launch — matching desktop's own gate (`App.kt:69-72`).
        .onChange(of: model.preferences?.localSettings.notificationEnabled, initial: true) { _, enabled in
            if enabled == true { OsNotificationPoster.requestAuthorization() }
        }
        #endif
    }

    #if os(macOS)
    private func configureAppDelegate() {
        appDelegate.model = model
        appDelegate.showMainWindow = { openWindow(id: "main") }
    }

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
