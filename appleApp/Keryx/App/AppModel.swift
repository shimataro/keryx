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
    private(set) var startupError: (any Error)?

    init() {
        do {
            let sdk = try KeryxSdk.companion.start(
                newArticlesText: { count in "\(count) new articles" },
                postOsNotification: { _, _ in },
                dataDirectory: nil,
                openAuthorization: nil,
                useDataProtectionKeychain: true
            )
            self.sdk = sdk
            self.home = HomeObservable(viewModel: sdk.homeViewModel)
        } catch {
            self.startupError = error
        }
    }
}
