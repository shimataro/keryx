#if os(macOS)
import AppKit
import KeryxShared
import UserNotifications

extension NSWindow {
    /// The About window (`KeryxApp`'s `Window(L("menu_help_about"), id: "about")`) is matched by
    /// title, not `id`, since AppKit's own `NSWindow` carries no SwiftUI scene identifier — every
    /// place that needs "every window except About" (tray toggle, main-window tracking) shares this
    /// one check instead of repeating the title comparison.
    var isAboutWindow: Bool { title == L("menu_help_about") }
}

/// Keeps the app running (hidden in the menu bar) after the last window closes, instead of
/// quitting — see `external-spec.md` §7's "task tray residence (close minimizes to tray)". Also
/// owns the tray's own `NSStatusItem` (a plain `MenuBarExtra` can't tell a left click from a right
/// one), flushes settings before quitting, applies the "start minimized" setting, shows a
/// notification banner even while the app is frontmost, and hands every foreground/Dock reopen
/// through `showMainWindow` (set by `KeryxApp` to `openWindow(id: "main")`, since only a `View`'s
/// environment carries that action).
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
        // Claims the main window's own close button the first time it becomes key — `windowShouldClose`
        // is the one delegate hook that can turn "close" into "hide" *before* AppKit tears the window
        // (and every SwiftUI view/task it hosts, including HomeView's own observation loops) down;
        // `willCloseNotification` above fires too late to prevent that. Skips the About/Settings
        // windows, which should still close normally.
        NotificationCenter.default.addObserver(
            forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let self, let window = note.object as? NSWindow, self.mainWindow == nil,
                  !window.isAboutWindow else { return }
            self.mainWindow = window
            window.delegate = self
        }
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

    /// Reopens (or activates) the main window when the Dock icon is clicked while none is visible.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        NSApp.setActivationPolicy(.regular)
        if !flag {
            showMainWindowOrReveal()
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
            for window in NSApp.windows { window.orderOut(nil) }
        }
    }

    private func updateActivationPolicySoon() {
        // Deferred a tick: at the moment a window-close notification fires, the closing window is
        // still in `NSApp.windows`, so counting visible windows synchronously here would always
        // find at least one (the one about to close/hide).
        DispatchQueue.main.async {
            let hasVisibleWindow = NSApp.windows.contains { $0.isVisible }
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
        (mainWindow?.isVisible ?? false) || NSApp.windows.contains { $0.isVisible && !$0.isAboutWindow }
    }

    @objc private func toggleMainWindow() {
        if mainWindowVisible {
            for window in NSApp.windows where !window.isAboutWindow { window.orderOut(nil) }
            NSApp.setActivationPolicy(.accessory)
        } else {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            showMainWindowOrReveal()
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
