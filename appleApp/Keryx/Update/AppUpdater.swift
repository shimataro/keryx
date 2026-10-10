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
///
/// Builds `SPUUpdater` itself rather than through `SPUStandardUpdaterController`, which cannot be
/// given a user driver: `KeryxUserDriver` replaces only Sparkle's message for a feed that cannot be
/// read. Everything else is the standard driver, as the controller would have created it.
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
            guard updater.automaticallyChecksForUpdates != automaticallyChecksForUpdates else { return }
            updater.automaticallyChecksForUpdates = automaticallyChecksForUpdates
        }
    }

    @ObservationIgnored private let updater: SPUUpdater
    /// Sparkle holds its user driver weakly, and the driver holds its delegate weakly.
    @ObservationIgnored private let userDriver: KeryxUserDriver
    @ObservationIgnored private let userDriverDelegate: UserDriverDelegate
    @ObservationIgnored private var canCheckObservation: NSKeyValueObservation?
    @ObservationIgnored private var automaticChecksObservation: NSKeyValueObservation?

    init() {
        let configured = Self.hasPublicKey
        let delegate = UserDriverDelegate()
        let driver = KeryxUserDriver(hostBundle: .main, delegate: delegate)
        let updater = SPUUpdater(
            hostBundle: .main, applicationBundle: .main, userDriver: driver, delegate: nil
        )
        isConfigured = configured
        userDriverDelegate = delegate
        userDriver = driver
        self.updater = updater
        if configured {
            do {
                try updater.start()
            } catch {
                // Sparkle's own controller alerts here too; the driver shows the error as an alert.
                Task { @MainActor in driver.showUpdaterError(error, acknowledgement: {}) }
            }
        }
        automaticallyChecksForUpdates = updater.automaticallyChecksForUpdates
        canCheckObservation = updater.observe(\.canCheckForUpdates, options: [.initial, .new]) {
            [weak self] _, change in
            let value = change.newValue ?? false
            Task { @MainActor in self?.canCheckForUpdates = value }
        }
        automaticChecksObservation = updater.observe(\.automaticallyChecksForUpdates, options: [.new]) {
            [weak self] _, _ in
            // Re-read rather than use the change's value: by the time this runs, a later toggle may
            // already have superseded it.
            Task { @MainActor in
                guard let self else { return }
                let current = self.updater.automaticallyChecksForUpdates
                if self.automaticallyChecksForUpdates != current { self.automaticallyChecksForUpdates = current }
            }
        }
    }

    /// The one entry point for "Check for Updates…" (the app menu item).
    func checkForUpdates() {
        updater.checkForUpdates()
    }

    private static var hasPublicKey: Bool {
        let key = Bundle.main.object(forInfoDictionaryKey: "SUPublicEDKey") as? String
        return key?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

/// Sparkle's standard user driver, except that an update check which could not read the feed says
/// what happened and what to do (see `UpdateCheckFailure`) instead of Sparkle's generic "error
/// occurred in retrieving update information".
///
/// Every override calls `super` exactly once on every path: the standard driver is what answers
/// Sparkle's `reply`/`acknowledgement`, and an override that skipped it would leave the update
/// session hanging until the app restarts.
@MainActor
private final class KeryxUserDriver: SPUStandardUserDriver {
    /// Whether the current check has found an update. A download error after that is the update
    /// file itself failing, which `UpdateCheckFailure`'s message would misdescribe.
    private var updateFound = false

    override func showUserInitiatedUpdateCheck(cancellation: @escaping () -> Void) {
        updateFound = false
        super.showUserInitiatedUpdateCheck(cancellation: cancellation)
    }

    override func showUpdateFound(
        with appcastItem: SUAppcastItem, state: SPUUserUpdateState,
        reply: @escaping (SPUUserUpdateChoice) -> Void
    ) {
        updateFound = true
        super.showUpdateFound(with: appcastItem, state: state, reply: reply)
    }

    override func dismissUpdateInstallation() {
        updateFound = false
        super.dismissUpdateInstallation()
    }

    override func showUpdaterError(_ error: any Error, acknowledgement: @escaping () -> Void) {
        super.showUpdaterError(Self.localized(error, updateFound: updateFound), acknowledgement: acknowledgement)
    }

    /// The error with a message of our own when it qualifies, otherwise `error` untouched. The
    /// standard driver shows `localizedDescription` as the title and, when present,
    /// `localizedRecoverySuggestion` as the body.
    private static func localized(_ error: any Error, updateFound: Bool) -> any Error {
        let nsError = error as NSError
        guard let failure = UpdateCheckFailure.classify(
            nsError, updateFound: updateFound,
            downloadErrorDomain: SUSparkleErrorDomain, downloadErrorCode: Int(SUError.downloadError.rawValue)
        ) else { return error }
        var info = nsError.userInfo
        info[NSLocalizedDescriptionKey] = L("apple_update_check_failed_title")
        info[NSLocalizedRecoverySuggestionErrorKey] = switch failure {
        case .offline: L("apple_update_check_failed_offline")
        case .unavailable: L("apple_update_check_failed_unavailable")
        }
        info[NSUnderlyingErrorKey] = nsError
        return NSError(domain: nsError.domain, code: nsError.code, userInfo: info)
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
