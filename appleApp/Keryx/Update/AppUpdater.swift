#if os(macOS)
import AppKit
import Foundation
import Observation
import Sparkle

/// The macOS app's self-update, delegated to Sparkle (the GitHub Release build; see
/// `docs/app-architecture.md`'s "Distribution and coexistence"). Sparkle owns the whole flow —
/// feed fetch, dialogs, download, install, relaunch — so this only exposes what the menu and the
/// General settings tab need. The Compose updater (`UpdateRepository` and friends) is not part of
/// this app.
///
/// Held by `AppModel` directly, not behind the SDK: an app whose database a newer build already
/// migrated cannot start the SDK at all, and updating is exactly the way out of that.
///
/// Stays off — never starts, makes no request — while `SUPublicEDKey` is empty (see
/// `Config/Shared.xcconfig`'s `SPARKLE_PUBLIC_ED_KEY`): `canCheckForUpdates` is then false, which
/// disables every control built on it.
@MainActor
@Observable
final class AppUpdater {
    /// Whether Sparkle may start a check right now (false while unconfigured or while a check or
    /// install is already underway). Mirrors `SPUUpdater.canCheckForUpdates`.
    private(set) var canCheckForUpdates = false

    /// Whether this build ships an update public key, i.e. whether the updater is running at all.
    let isConfigured: Bool

    /// The scheduled-check setting, kept by Sparkle itself (`UserDefaults`); mirrored here so
    /// SwiftUI sees changes. Sparkle changes it too (the user's answer to its permission prompt),
    /// which reaches the mirror through KVO.
    var automaticallyChecksForUpdates: Bool {
        didSet {
            // Skip the KVO echo: writing back would re-fire KVO and reset Sparkle's check schedule.
            guard controller.updater.automaticallyChecksForUpdates != automaticallyChecksForUpdates else { return }
            controller.updater.automaticallyChecksForUpdates = automaticallyChecksForUpdates
        }
    }

    @ObservationIgnored private let controller: SPUStandardUpdaterController
    /// Sparkle holds its user-driver delegate weakly.
    @ObservationIgnored private let userDriverDelegate: UserDriverDelegate
    @ObservationIgnored private var canCheckObservation: NSKeyValueObservation?
    @ObservationIgnored private var automaticChecksObservation: NSKeyValueObservation?

    init() {
        let configured = Self.hasPublicKey
        let delegate = UserDriverDelegate()
        let controller = SPUStandardUpdaterController(
            startingUpdater: configured, updaterDelegate: nil, userDriverDelegate: delegate
        )
        isConfigured = configured
        userDriverDelegate = delegate
        self.controller = controller
        automaticallyChecksForUpdates = controller.updater.automaticallyChecksForUpdates
        canCheckObservation = controller.updater.observe(\.canCheckForUpdates, options: [.initial, .new]) {
            [weak self] _, change in
            let value = change.newValue ?? false
            Task { @MainActor in self?.canCheckForUpdates = value }
        }
        automaticChecksObservation = controller.updater.observe(\.automaticallyChecksForUpdates, options: [.new]) {
            [weak self] _, _ in
            // Re-read rather than use the change's value: by the time this runs, a later toggle may
            // already have superseded it.
            Task { @MainActor in
                guard let self else { return }
                let current = self.controller.updater.automaticallyChecksForUpdates
                if self.automaticallyChecksForUpdates != current { self.automaticallyChecksForUpdates = current }
            }
        }
    }

    /// The one entry point for "Check for Updates…" (the app menu item).
    func checkForUpdates() {
        controller.checkForUpdates(nil)
    }

    private static var hasPublicKey: Bool {
        let key = Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String
        return key?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

/// Brings the app forward when a scheduled check finds an update while it is resident in the menu
/// bar. `AppDelegate` drops to `.accessory` once no window is visible, and an accessory app's alert
/// would otherwise open behind whatever is frontmost. A check the user started (the menu item) needs
/// no help: the app is already active. Once Sparkle's window closes, `AppDelegate`'s own
/// window-close handling returns the app to `.accessory` if nothing else is showing.
/// See Sparkle's "Gentle reminders" documentation.
@MainActor
private final class UserDriverDelegate: NSObject, @preconcurrency SPUStandardUserDriverDelegate {
    var supportsGentleScheduledUpdateReminders: Bool { true }

    func standardUserDriverWillHandleShowingUpdate(
        _ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState
    ) {
        guard handleShowingUpdate, !state.userInitiated else { return }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }
}
#endif
