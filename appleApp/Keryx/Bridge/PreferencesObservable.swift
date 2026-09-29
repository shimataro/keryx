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
    // The fields the always-on-screen views read, mirrored one by one. `localSettings` itself is
    // replaced on every write — including the `lastArticleId`/`lastFocusedPane` bookkeeping each
    // article selection and focus change does — so a view reading a field through it would be
    // invalidated by all of those too. These are only assigned when their own value changes.
    private(set) var themeMode: String
    private(set) var fontSizeScale: Double
    private(set) var feedListPaneWidth: Double
    private(set) var articleListPaneWidth: Double
    private(set) var notificationEnabled: Bool
    private(set) var readTimeoutSeconds: Int
    private(set) var cacheRetentionDays: Int?

    init(controller: PreferencesController) {
        self.controller = controller
        let settings = controller.localSettings.value
        localSettings = settings
        themeMode = settings.themeMode
        fontSizeScale = settings.fontSizeScale
        feedListPaneWidth = settings.feedListPaneWidth
        articleListPaneWidth = settings.articleListPaneWidth
        notificationEnabled = settings.notificationEnabled
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
        for await v in controller.localSettings {
            localSettings = v
            assignIfChanged(\.themeMode, v.themeMode)
            assignIfChanged(\.fontSizeScale, v.fontSizeScale)
            assignIfChanged(\.feedListPaneWidth, v.feedListPaneWidth)
            assignIfChanged(\.articleListPaneWidth, v.articleListPaneWidth)
            assignIfChanged(\.notificationEnabled, v.notificationEnabled)
        }
    }

    private func assignIfChanged<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<PreferencesObservable, Value>, _ value: Value) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    private func observeReadTimeoutSeconds() async {
        for await v in controller.readTimeoutSeconds { readTimeoutSeconds = Int(v.int32Value) }
    }

    private func observeCacheRetentionDays() async {
        for await v in controller.cacheRetentionDays { cacheRetentionDays = v.map { Int($0.int32Value) } }
    }
}
