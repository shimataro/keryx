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
    @Environment(\.scenePhase) private var scenePhase

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
                // iOS applies the in-app theme as the window's `overrideUserInterfaceStyle` instead
                // of `.preferredColorScheme`: the latter reaches a presented sheet only when the
                // sheet opens, so the open Settings sheet kept its old appearance after the theme
                // was switched from it.
                .background(WindowInterfaceStyle(style: interfaceStyle(for: model.preferences?.themeMode)))
        }
        // The OS wakes the app for this (see `BackgroundRefresh`); it cancels the closure at the
        // end of its time slot, which cancels the shared refresh with it.
        .backgroundTask(.appRefresh(BackgroundRefresh.taskIdentifier)) {
            await model.runBackgroundRefresh()
        }
        .commands { HomeCommands(model: model) }
        #endif

        #if os(macOS)
        // The `Settings` scene (Cmd+, / the app menu's "Settings…") is a macOS-only concept — iOS
        // has no equivalent scene type, so there Settings is a sheet over the main window instead
        // (`settingsSheet` in `mainContent`).
        Settings {
            if let sdk = model.sdk, let preferences = model.preferences, let cloudSync = model.cloudSync,
               let opmlTransfer = model.opmlTransfer {
                SettingsView(
                    sdk: sdk, preferences: preferences, cloudSync: cloudSync,
                    oauthCoordinator: model.oauthCoordinator, opmlTransfer: opmlTransfer,
                    settingsNavigation: model.settingsNavigation
                )
                .environment(model.updater)
            }
        }
        .windowResizability(.contentSize)

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
                } else if let notifications = model.notifications, let opmlTransfer = model.opmlTransfer {
                    HomeView(home: home, sidebarDialogs: model.sidebarDialogs, notifications: notifications, settingsNavigation: model.settingsNavigation, preferences: preferences)
                        .modifier(OpmlRequestPresenter(opmlTransfer: opmlTransfer, settingsNavigation: model.settingsNavigation))
                        .modifier(UnreadCountObserver(home: home) { count in
                            #if os(macOS)
                            appDelegate.updateUnreadIndicators(unreadCount: count)
                            #else
                            updateAppIconBadge(unreadCount: count)
                            #endif
                        })
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
        #if os(macOS)
        // Applies the in-app theme setting to every SwiftUI-rendered surface — Settings/About
        // scenes read it independently through their own environment inheritance. The preferences
        // themselves are observed by `AppModel` for the app's whole lifetime, so this takes effect
        // before Settings is ever opened. `NSApp.appearance` additionally covers the surfaces
        // `preferredColorScheme` does not reach: native menus, and any AppKit chrome outside this
        // scene's own view tree.
        .preferredColorScheme(colorScheme(for: model.preferences?.themeMode))
        #endif
        #if os(iOS)
        .sheet(isPresented: Binding(
            get: { model.settingsNavigation.isSheetPresented },
            set: { model.settingsNavigation.isSheetPresented = $0 }
        )) {
            settingsSheet
        }
        #endif
        #if os(macOS)
        .onChange(of: model.preferences?.themeMode, initial: true) { _, mode in
            applyAppearance(mode)
        }
        #endif
        // Requested once the setting is on and first-run Setup is over (so the system prompt does not
        // pop up over the Setup screen), and again the moment it's switched on — never
        // unconditionally at every launch. See `shouldRequestNotificationAuthorization`.
        .onChange(of: shouldRequestNotificationAuthorization(
            enabled: model.preferences?.notificationEnabled, needsSetup: model.needsSetup
        ), initial: true) { _, shouldRequest in
            if shouldRequest { OsNotificationPoster.requestAuthorization() }
        }
        #if os(iOS)
        // iOS runs its maintenance once the scene first becomes active (not from `AppModel.init`,
        // which a background launch also runs), keeps its refresh loop to the foreground, and keeps
        // the next background refresh scheduled.
        .onChange(of: scenePhase, initial: true) { _, phase in
            switch phase {
            case .active: model.startForegroundWorkIfNeeded()
            case .background:
                scheduleBackgroundRefresh()
                model.stopForegroundLoop()
                model.flushPendingSync()
            default: break
            }
        }
        .onChange(of: model.preferences?.refreshIntervalMinutes) { _, _ in scheduleBackgroundRefresh() }
        #endif
    }

    #if os(iOS)
    private func scheduleBackgroundRefresh() {
        guard let minutes = model.preferences?.refreshIntervalMinutes else { return }
        BackgroundRefresh.schedule(refreshIntervalMinutes: minutes)
    }

    /// Mirrors `resolveDarkTheme`'s `"light"`/`"dark"`/else (follow system) rule (`KeryxTheme.kt`)
    /// for the window override `WindowInterfaceStyle` applies — `.unspecified` leaves the system
    /// setting in charge.
    private func interfaceStyle(for mode: String?) -> UIUserInterfaceStyle {
        switch mode {
        case "light": .light
        case "dark": .dark
        default: .unspecified
        }
    }

    /// The iOS counterpart of the macOS `Settings` scene above, presented from the sidebar's gear
    /// button or a bell row's `ShowSettingsTab` (`SettingsNavigation.isSheetPresented`).
    @ViewBuilder
    private var settingsSheet: some View {
        if let sdk = model.sdk, let preferences = model.preferences, let cloudSync = model.cloudSync,
           let opmlTransfer = model.opmlTransfer {
            SettingsView(
                sdk: sdk, preferences: preferences, cloudSync: cloudSync,
                oauthCoordinator: model.oauthCoordinator, opmlTransfer: opmlTransfer,
                settingsNavigation: model.settingsNavigation
            )
            // No `.preferredColorScheme` of its own: the sheet inherits the window's
            // `overrideUserInterfaceStyle` (`WindowInterfaceStyle`), which also follows a theme change
            // made while the sheet is open.
        }
    }
    #endif

    #if os(macOS)
    private func configureAppDelegate() {
        appDelegate.model = model
        appDelegate.showMainWindow = { openWindow(id: "main") }
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

    /// Mirrors `resolveDarkTheme`'s same rule for SwiftUI's own `.preferredColorScheme` — `nil`
    /// leaves the system setting in charge.
    private func colorScheme(for mode: String?) -> ColorScheme? {
        switch mode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
    #endif
}

/// Reports `home.totalUnread` (the Dock badge and the menu bar status item on macOS, the app icon
/// badge on iOS) from a modifier of its
/// own, so only this modifier — not `KeryxApp`'s whole main content — depends on the count, which
/// changes with every article read.
private struct UnreadCountObserver: ViewModifier {
    let home: HomeObservable
    let onChange: (Int64) -> Void

    func body(content: Content) -> some View {
        content.onChange(of: home.totalUnread, initial: true) { _, count in onChange(count) }
    }
}
