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
    /// The provider whose connect is running — Nextcloud's says it waits on the browser sign-in.
    private(set) var connectingType: CloudStorageType?

    init(controller: SetupController) {
        self.controller = controller
    }

    func startObserving() async {
        async let t1: () = observePhase()
        async let t2: () = observeCanCancelConnect()
        async let t3: () = observeConnectingType()
        _ = await (t1, t2, t3)
    }

    private func observeConnectingType() async {
        for await v in controller.connectingType { connectingType = v }
    }

    private func observePhase() async {
        for await v in controller.phase { phase = v }
    }

    private func observeCanCancelConnect() async {
        for await v in controller.canCancelConnect { canCancelConnect = v.boolValue }
    }
}
