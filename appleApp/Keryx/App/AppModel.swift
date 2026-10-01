import Foundation
import KeryxShared
import Observation

/// Builds and holds the shared `KeryxSdk` graph for the lifetime of the app, and the
/// `@Observable` adapters SwiftUI views read from. One instance per process — see
/// `docs/app-architecture.md`'s "Apple Native Apps (SwiftUI)".
@MainActor
@Observable
final class AppModel {
    /// `nil` only when `start()` failed — currently the one documented failure is
    /// `DatabaseTooNewException` (a newer build already migrated this device's database). The app
    /// shows an explanatory screen instead of the normal UI in that case.
    private(set) var sdk: KeryxSdk?
    private(set) var home: HomeObservable?
    private(set) var preferences: PreferencesObservable?
    private(set) var cloudSync: CloudSyncObservable?
    private(set) var opmlTransfer: OpmlTransferObservable?
    private(set) var notifications: NotificationCenterObservable?
    private(set) var startupError: (any Error)?
    /// Whether the first-launch Setup screen should show instead of Home — read once at startup;
    /// `SetupView`'s `onDone` flips this directly rather than this property re-polling
    /// `isSetupComplete()` reactively (there is nothing else that would change it mid-session).
    private(set) var needsSetup = false

    /// Sidebar dialog state (add feed / create-rename-delete folder-tag / unsubscribe confirm) —
    /// owned here rather than by `HomeView` so both `FeedListView`'s own context menus and the
    /// app-level `Commands`/keyboard handling can trigger the same dialogs.
    let sidebarDialogs = SidebarDialogState()
    let oauthCoordinator = OAuthSessionCoordinator()
    /// The Settings window's selected tab — see `SettingsNavigation`'s own doc for why it lives here.
    let settingsNavigation = SettingsNavigation()

    init() {
        do {
            let sdk = try Self.startSdk(oauthCoordinator: oauthCoordinator)
            self.sdk = sdk
            oauthCoordinator.sdk = sdk
            self.home = HomeObservable(
                viewModel: sdk.homeViewModel,
                // `newAddFeedController` in Kotlin — Swift sees `doNewAddFeedController()` because
                // ObjC's "new"-prefix method-family convention (implicitly-owned return) requires
                // Kotlin/Native's ObjC export to rename any Kotlin method literally named `newXxx`.
                makeAddFeedController: { sdk.doNewAddFeedController() }
            )
            let preferences = PreferencesObservable(controller: sdk.preferences)
            self.preferences = preferences
            // The one observation of the preferences, for the app's lifetime: the theme and the
            // Settings window both need it with or without the main window open (see
            // `PreferencesObservable.startObserving`).
            Task { await preferences.startObserving() }
            self.cloudSync = CloudSyncObservable(controller: sdk.cloudSyncController)
            self.opmlTransfer = OpmlTransferObservable(opml: sdk.opml)
            self.notifications = NotificationCenterObservable(center: sdk.notificationCenter)
            self.needsSetup = !sdk.settingsRepository.isSetupComplete()
            // Requesting authorization is `KeryxApp`'s job now, gated on `notificationEnabled`
            // (both at startup and whenever the setting is switched on) — see its own
            // `.onChange(of: model.preferences?.notificationEnabled)`, matching
            // desktop's own gate (`App.kt:69-72`).
            try sdk.startMaintenance()
            Task {
                try? await sdk.prepareSearchIndex()
            }
        } catch {
            self.startupError = error
        }
    }

    /// `nonisolated` on purpose: Kotlin invokes these callbacks from its own background dispatchers
    /// (e.g. `NewArticleNotifier` runs on `Dispatchers.Default` when a background/startup refresh
    /// finds new articles). Closures written inside the `@MainActor` `init` would inherit main-actor
    /// isolation, and Swift 6 guards each such closure with a runtime executor check that traps when
    /// Kotlin calls it off the main thread — see `docs/app-architecture.md`'s "`KeryxSdk`: the Swift
    /// entry point" for the calling convention this satisfies. Every callee below is safe on any
    /// thread (see each type's own doc): `L`/`LF`, `OsNotificationPoster.post`, and
    /// `OAuthSessionCoordinator.open` (which itself hops to `@MainActor` internally).
    nonisolated private static func startSdk(oauthCoordinator: OAuthSessionCoordinator) throws -> KeryxSdk {
        try KeryxSdk.companion.start(
            newArticlesText: { count in LF("home_new_articles", Int64(count)) },
            postOsNotification: { message, _ in OsNotificationPoster.post(message: message) },
            // KERYX_DATA_DIR lets a manual verification run point at a scratch directory instead
            // of the real ~/Library/Application Support/Keryx — unset (nil) in every normal
            // launch, which keeps production behavior unchanged.
            dataDirectory: ProcessInfo.processInfo.environment["KERYX_DATA_DIR"],
            openAuthorization: { url, scheme in
                oauthCoordinator.open(url: url, callbackScheme: scheme)
            },
            useDataProtectionKeychain: true
        )
    }

    func completeSetup() {
        needsSetup = false
    }

    /// Imports an `.opml` document the app was opened with (`onOpenURL`) — see
    /// `KeryxSdk.importOpenedOpml`'s own doc.
    func importOpenedOpml(url: URL) {
        guard let sdk else { return }
        // Ignores the boolean result: LaunchServices delivering a document this way already grants
        // the access `.fileImporter`'s own security-scoped URLs need `startAccessing…` to unlock, so
        // requiring it to return `true` here silently dropped every normal file-association open.
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { url.stopAccessingSecurityScopedResource() } }
        guard let xml = try? String(contentsOf: url, encoding: .utf8) else { return }
        Task {
            try? await sdk.importOpenedOpml(xml: xml)
        }
    }
}
