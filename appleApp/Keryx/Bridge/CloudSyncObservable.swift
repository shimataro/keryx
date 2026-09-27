import KeryxShared
import Observation

/// Mirrors `CloudSyncController`'s `StateFlow`s as `@Observable` properties — see
/// `HomeObservable`'s own doc for the pattern.
@MainActor
@Observable
final class CloudSyncObservable {
    let controller: CloudSyncController

    private(set) var connectedType: CloudStorageType?
    private(set) var connectingType: CloudStorageType?
    private(set) var initialSyncingType: CloudStorageType?
    private(set) var connectFailedType: CloudStorageType?
    private(set) var canCancelConnect: Bool = false
    private(set) var resetting: Bool = false
    private(set) var lastSyncedAtText: String?
    private(set) var lastSyncError: ErrorKind?
    private(set) var lastSyncAuthFailed: Bool = false
    private(set) var syncing: Bool = false
    private(set) var syncPhase: SyncPhase = .idle
    private(set) var disconnecting: Bool = false
    private(set) var idle: Bool = true
    private(set) var canSyncNow: Bool = false

    var availableCloudTypes: [CloudStorageType] { controller.availableCloudTypes }

    init(controller: CloudSyncController) {
        self.controller = controller
    }

    func startObserving() async {
        async let t1: () = observeConnectedType()
        async let t2: () = observeConnectingType()
        async let t3: () = observeInitialSyncingType()
        async let t4: () = observeConnectFailedType()
        async let t5: () = observeCanCancelConnect()
        async let t6: () = observeResetting()
        async let t7: () = observeLastSyncedAtText()
        async let t8: () = observeLastSyncError()
        async let t9: () = observeLastSyncAuthFailed()
        async let t10: () = observeSyncing()
        async let t11: () = observeSyncPhase()
        async let t12: () = observeDisconnecting()
        async let t13: () = observeIdle()
        async let t14: () = observeCanSyncNow()
        _ = await (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)
    }

    private func observeConnectedType() async {
        for await v in controller.connectedType { connectedType = v }
    }

    private func observeConnectingType() async {
        for await v in controller.connectingType { connectingType = v }
    }

    private func observeInitialSyncingType() async {
        for await v in controller.initialSyncingType { initialSyncingType = v }
    }

    private func observeConnectFailedType() async {
        for await v in controller.connectFailedType { connectFailedType = v }
    }

    private func observeCanCancelConnect() async {
        for await v in controller.canCancelConnect { canCancelConnect = v.boolValue }
    }

    private func observeResetting() async {
        for await v in controller.resetting { resetting = v.boolValue }
    }

    private func observeLastSyncedAtText() async {
        for await v in controller.lastSyncedAtText { lastSyncedAtText = v }
    }

    private func observeLastSyncError() async {
        for await v in controller.lastSyncError { lastSyncError = v }
    }

    private func observeLastSyncAuthFailed() async {
        for await v in controller.lastSyncAuthFailed { lastSyncAuthFailed = v.boolValue }
    }

    private func observeSyncing() async {
        for await v in controller.syncing { syncing = v.boolValue }
    }

    private func observeSyncPhase() async {
        for await v in controller.syncPhase { syncPhase = v }
    }

    private func observeDisconnecting() async {
        for await v in controller.disconnecting { disconnecting = v.boolValue }
    }

    private func observeIdle() async {
        for await v in controller.idle { idle = v.boolValue }
    }

    private func observeCanSyncNow() async {
        for await v in controller.canSyncNow { canSyncNow = v.boolValue }
    }
}
