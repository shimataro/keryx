import KeryxShared
import Observation

/// Mirrors `PreferencesController`'s `StateFlow`s as `@Observable` properties — see
/// `HomeObservable`'s own doc for the pattern. Every setter is a plain, synchronous call straight
/// through to `PreferencesController` (it owns no coroutine scope of its own).
@MainActor
@Observable
final class PreferencesObservable {
    let controller: PreferencesController

    private(set) var localSettings: LocalSettings?
    private(set) var readTimeoutSeconds: Int = 30
    private(set) var cacheRetentionDays: Int?

    init(controller: PreferencesController) {
        self.controller = controller
    }

    func startObserving() async {
        async let t1: () = observeLocalSettings()
        async let t2: () = observeReadTimeoutSeconds()
        async let t3: () = observeCacheRetentionDays()
        _ = await (t1, t2, t3)
    }

    private func observeLocalSettings() async {
        for await v in controller.localSettings { localSettings = v }
    }

    private func observeReadTimeoutSeconds() async {
        for await v in controller.readTimeoutSeconds { readTimeoutSeconds = Int(v.int32Value) }
    }

    private func observeCacheRetentionDays() async {
        for await v in controller.cacheRetentionDays { cacheRetentionDays = v.map { Int($0.int32Value) } }
    }
}
