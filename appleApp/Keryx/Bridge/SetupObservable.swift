import KeryxShared
import Observation

/// Mirrors `SetupController`'s `StateFlow`s as `@Observable` properties, the same pattern
/// `HomeObservable` uses for `HomeViewModel`.
@MainActor
@Observable
final class SetupObservable {
    let controller: SetupController

    private(set) var phase: SetupPhase = .idle
    private(set) var canCancelConnect: Bool = false

    init(controller: SetupController) {
        self.controller = controller
    }

    func startObserving() async {
        async let t1: () = observePhase()
        async let t2: () = observeCanCancelConnect()
        _ = await (t1, t2)
    }

    private func observePhase() async {
        for await v in controller.phase { phase = v }
    }

    private func observeCanCancelConnect() async {
        for await v in controller.canCancelConnect { canCancelConnect = v.boolValue }
    }
}
