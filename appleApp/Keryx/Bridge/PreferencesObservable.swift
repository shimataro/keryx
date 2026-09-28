import KeryxShared
import Observation

/// Mirrors `PreferencesController`'s `StateFlow`s as `@Observable` properties — see
/// `HomeObservable`'s own doc for the pattern. Every setter is a plain, synchronous call straight
/// through to `PreferencesController` (it owns no coroutine scope of its own).
///
/// Each property's initial value is read synchronously from its `StateFlow`'s own `.value` at
/// construction time — `SettingsRepository`'s backing `MutableStateFlow` is itself seeded
/// synchronously from disk (`MutableStateFlow(store.load())`), so there is no genuine load delay to
/// wait out. Waiting for `startObserving()`'s first `for await` emission instead (as this used to)
/// left every property at its placeholder default for one run-loop turn, during which `HomeView`'s
/// own `onAppear` (pane/width restore) and `KeryxApp`'s theme observers would already have read and
/// even persisted that placeholder over whatever was actually saved.
@MainActor
@Observable
final class PreferencesObservable {
    let controller: PreferencesController

    private(set) var localSettings: LocalSettings
    private(set) var readTimeoutSeconds: Int
    private(set) var cacheRetentionDays: Int?

    init(controller: PreferencesController) {
        self.controller = controller
        localSettings = controller.localSettings.value
        readTimeoutSeconds = Int(controller.readTimeoutSeconds.value.int32Value)
        cacheRetentionDays = controller.cacheRetentionDays.value.map { Int($0.int32Value) }
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
