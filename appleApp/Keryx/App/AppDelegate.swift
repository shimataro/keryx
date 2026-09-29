#if os(macOS)
import AppKit
import KeryxShared
import UserNotifications

extension NSWindow {
    /// The About window (`KeryxApp`'s `Window(L("menu_help_about"), id: "about")`) is matched by
    /// title, not `id`, since AppKit's own `NSWindow` carries no SwiftUI scene identifier — every
    /// place that needs "every window except About" (tray toggle and its label) shares this
    /// one check instead of repeating the title comparison.
    var isAboutWindow: Bool { title == L("menu_help_about") }

    /// Whether this is one of the app's own content windows (main, Settings, About) rather than
    /// AppKit's own chrome. `NSApp.windows` also holds the tray icon's `NSStatusBarWindow` — always
    /// `isVisible` while the status item exists — plus tooltip panels and menus; counting those as
    /// "a visible window" kept the Dock icon up and the tray toggle stuck on "hide", and ordering
    /// the status-bar window out along with the rest broke the next visibility check. None of them
    /// can become main, so `canBecomeMain` tells them apart.
    var isAppContentWindow: Bool { canBecomeMain }
}

/// Keeps the app running (hidden in the menu bar) after the last window closes, instead of
/// quitting — see `external-spec.md` §7's "task tray residence (close minimizes to tray)". Also
/// owns the tray's own `NSStatusItem` (a plain `MenuBarExtra` can't tell a left click from a right
/// one), flushes settings before quitting, applies the "start minimized" setting, shows a
/// notification banner even while the app is frontmost, and hands every tray/Dock/document reopen
/// through `revealMainWindow` — which falls back to `showMainWindow` (set by `KeryxApp` to
/// `openWindow(id: "main")`, since only a `View`'s environment carries that action) when no main
/// window exists yet.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, @preconcurrency UNUserNotificationCenterDelegate {
    /// Set by `KeryxApp` once its own properties are ready (before this delegate's own launch
    /// callbacks fire) — `AppDelegate` itself has no access to the SDK or SwiftUI's environment.
    var model: AppModel?
    var showMainWindow: (() -> Void)?

    private var statusItem: NSStatusItem?
    private weak var mainWindow: NSWindow?
    private var isTerminating = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = self
        setUpStatusItem()
        applyStartMinimizedIfNeeded()

        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.updateActivationPolicySoon()
        }
    }

    /// Called by `KeryxApp`'s `MainWindowReader` with the window hosting the main scene. Claims
    /// its close button — `windowShouldClose` is the one delegate hook that can turn "close" into
    /// "hide" *before* AppKit tears the window (and every SwiftUI view/task it hosts, including
    /// HomeView's own observation loops) down; `willCloseNotification` above fires too late to
    /// prevent that. Registered by reference rather than inferred from key-window changes, so a
    /// window hidden before it ever became key ("start minimized") is still the one the tray and
    /// Dock reveal, instead of `showMainWindow` opening a second one.
    func registerMainWindow(_ window: NSWindow) {
        guard window !== mainWindow else { return }
        mainWindow = window
        window.delegate = self
    }

    /// Turning "close" into "hide" for the main window only — About/Settings should still close for
    /// real when dismissed.
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard sender === mainWindow else { return true }
        sender.orderOut(nil)
        updateActivationPolicySoon()
        return false
    }

    /// Flushes pending settings writes before quitting — mirrors desktop's own JVM shutdown hook
    /// (`main.kt`'s `exitApp`). `.terminateLater` lets the async flush finish before the process
    /// actually exits; `reply(toApplicationShouldTerminate:)` resumes AppKit's own termination
    /// sequence once it has.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard !isTerminating, let sdk = model?.sdk else { return .terminateNow }
        isTerminating = true
        Task { @MainActor in
            try? await sdk.settingsRepository.flush()
            NSApp.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }

    /// The main window is only ever hidden, never closed (`windowShouldClose` above), so this only
    /// matters for Settings/About: closing one of those while the main window sits in the tray must
    /// not quit the app. Stated explicitly rather than relying on SwiftUI's own default, which is
    /// not guaranteed for an app whose main scene is a single `Window`.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    /// Reopens (or activates) the main window when the Dock icon is clicked while none is visible.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if flag {
            NSApp.setActivationPolicy(.regular)
        } else {
            revealMainWindow()
        }
        return true
    }

    /// Shows a banner even while this app is frontmost — `UNUserNotificationCenter`'s own default
    /// (with no delegate) suppresses a notification while its owning app is active, unlike desktop's
    /// tray balloon, which always shows (`MacTray.kt`'s `TrayIcon.displayMessage`).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    private func applyStartMinimizedIfNeeded() {
        guard model?.sdk?.settingsRepository.getLocalSettings().startMinimized == true else { return }
        NSApp.setActivationPolicy(.accessory)
        DispatchQueue.main.async {
            for window in NSApp.windows where window.isAppContentWindow { window.orderOut(nil) }
        }
    }

    private func updateActivationPolicySoon() {
        // Deferred a tick: at the moment a window-close notification fires, the closing window is
        // still in `NSApp.windows`, so counting visible windows synchronously here would always
        // find at least one (the one about to close/hide). Only content windows count — see
        // `isAppContentWindow` for why the status-bar window must not.
        DispatchQueue.main.async {
            let hasVisibleWindow = NSApp.windows.contains { $0.isVisible && $0.isAppContentWindow }
            NSApp.setActivationPolicy(hasVisibleWindow ? .regular : .accessory)
        }
    }

    // MARK: - Tray (`NSStatusItem`, not `MenuBarExtra`)

    /// A plain `MenuBarExtra` always opens its menu on click, with no way to tell a left click
    /// (toggle the window, matching desktop's own `MacTray.kt`) from a right click (show the menu)
    /// — an `NSStatusItem` built directly gets both.
    private func setUpStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.target = self
        item.button?.action = #selector(statusItemClicked)
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item
        updateStatusItemAppearance()
    }

    @objc private func statusItemClicked() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            showTrayMenu()
        } else {
            toggleMainWindow()
        }
    }

    private func showTrayMenu() {
        guard let button = statusItem?.button else { return }
        let menu = NSMenu()
        let toggleItem = NSMenuItem(
            title: L(mainWindowVisible ? "tray_hide" : "tray_show"),
            action: #selector(toggleMainWindow),
            keyEquivalent: ""
        )
        toggleItem.target = self
        menu.addItem(toggleItem)
        menu.addItem(.separator())
        let quitItem = NSMenuItem(title: L("tray_quit"), action: #selector(NSApplication.terminate), keyEquivalent: "")
        menu.addItem(quitItem)
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: button.bounds.maxY), in: button)
    }

    private var mainWindowVisible: Bool {
        (mainWindow?.isVisible ?? false)
            || NSApp.windows.contains { $0.isVisible && $0.isAppContentWindow && !$0.isAboutWindow }
    }

    @objc private func toggleMainWindow() {
        if mainWindowVisible {
            for window in NSApp.windows where window.isAppContentWindow && !window.isAboutWindow {
                window.orderOut(nil)
            }
            NSApp.setActivationPolicy(.accessory)
        } else {
            revealMainWindow()
        }
    }

    /// Brings the main window back to the front from wherever it is (tray-hidden, backgrounded,
    /// minimized). Promotes to Regular and activates *first*, then shows the window a tick later —
    /// the same order as desktop's own `activationRequests` handler (`main.kt`): an order-front
    /// issued in the same tick as the Accessory -> Regular transition can be dropped by the window
    /// server, leaving the window restored but behind other apps.
    ///
    /// Deliberately the forcing `activate(ignoringOtherApps: true)`, not macOS 14's `activate()`:
    /// the latter is cooperative — it only succeeds if the frontmost app yields activation, which
    /// nothing does for a tray click, so the window would come back behind the frontmost app. This
    /// matches desktop's `MacActivationPolicy.setDockIconVisible` (`activateIgnoringOtherApps:`).
    func revealMainWindow() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        DispatchQueue.main.async { [weak self] in
            self?.showMainWindowOrReveal()
        }
    }

    private func showMainWindowOrReveal() {
        if let mainWindow {
            if mainWindow.isMiniaturized { mainWindow.deminiaturize(nil) }
            mainWindow.makeKeyAndOrderFront(nil)
        } else {
            showMainWindow?()
        }
    }

    /// Called from `KeryxApp`'s own unread-count observation, kept alive for the app's whole
    /// lifetime (not tied to the main window's own SwiftUI content, which this `AppDelegate`
    /// exists independently of) — see `KeryxApp.swift`'s own `.onChange(of: home.totalUnread)`.
    func updateStatusItemAppearance(unreadCount: Int64 = 0) {
        statusItem?.button?.image = NSImage(
            systemSymbolName: unreadCount > 0 ? "envelope.badge.fill" : "envelope",
            accessibilityDescription: nil
        )
        statusItem?.button?.toolTip = "\(L("app_name")) (\(unreadCount))"
    }
}
#endif
