#if os(macOS)
import AppKit

/// Keeps the app running (hidden in the menu bar, via `MenuBarExtra`) after the last window
/// closes, instead of quitting — see `external-spec.md` §7's "task tray residence (close
/// minimizes to tray)". Switches the Dock/app-switcher presence (`NSApplication.ActivationPolicy`)
/// to match: `.regular` (Dock icon, app switcher entry) while any real window is visible,
/// `.accessory` (menu-bar only) once none are.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NotificationCenter.default.addObserver(
            forName: NSWindow.willCloseNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.updateActivationPolicySoon()
        }
    }

    /// Reopens (or activates) a window when the Dock icon is clicked while none is visible —
    /// `MenuBarExtra`'s own "Show" item does the same via `openWindow`, this covers the Dock path.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        NSApp.setActivationPolicy(.regular)
        if !flag {
            for window in NSApp.windows { window.makeKeyAndOrderFront(nil) }
        }
        return true
    }

    private func updateActivationPolicySoon() {
        // Deferred a tick: at the moment `willCloseNotification` fires, the closing window is
        // still in `NSApp.windows`, so counting visible windows synchronously here would always
        // find at least one (the one about to close).
        DispatchQueue.main.async {
            let hasVisibleWindow = NSApp.windows.contains { $0.isVisible }
            NSApp.setActivationPolicy(hasVisibleWindow ? .regular : .accessory)
        }
    }
}
#endif
