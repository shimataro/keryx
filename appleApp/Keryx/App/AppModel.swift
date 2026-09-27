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
    private(set) var startupError: (any Error)?
    /// Whether the first-launch Setup screen should show instead of Home — read once at startup;
    /// `SetupView`'s `onDone` flips this directly rather than this property re-polling
    /// `isSetupComplete()` reactively (there is nothing else that would change it mid-session).
    private(set) var needsSetup = false

    let oauthCoordinator = OAuthSessionCoordinator()

    init() {
        do {
            let sdk = try KeryxSdk.companion.start(
                newArticlesText: { count in LF("apple_new_articles_pill", Int64(count)) },
                postOsNotification: { _, _ in },
                // KERYX_DATA_DIR lets a manual verification run point at a scratch directory
                // instead of the real ~/Library/Application Support/Keryx — unset (nil) in every
                // normal launch, which keeps production behavior unchanged.
                dataDirectory: ProcessInfo.processInfo.environment["KERYX_DATA_DIR"],
                // Captures `oauthCoordinator` directly (not `self`), since `self` isn't fully
                // initialized yet at this point — the coordinator's own `sdk` back-reference is
                // wired below, once `start()` has actually returned an instance to point it at.
                openAuthorization: { [oauthCoordinator] url, scheme in
                    oauthCoordinator.open(url: url, callbackScheme: scheme)
                },
                useDataProtectionKeychain: true
            )
            self.sdk = sdk
            oauthCoordinator.sdk = sdk
            self.home = HomeObservable(
                viewModel: sdk.homeViewModel,
                // `newAddFeedController` in Kotlin — Swift sees `doNewAddFeedController()` because
                // ObjC's "new"-prefix method-family convention (implicitly-owned return) requires
                // Kotlin/Native's ObjC export to rename any Kotlin method literally named `newXxx`.
                makeAddFeedController: { sdk.doNewAddFeedController() }
            )
            self.preferences = PreferencesObservable(controller: sdk.preferences)
            self.cloudSync = CloudSyncObservable(controller: sdk.cloudSyncController)
            self.needsSetup = !sdk.settingsRepository.isSetupComplete()
            try sdk.startMaintenance()
        } catch {
            self.startupError = error
        }
    }

    func completeSetup() {
        needsSetup = false
    }
}
