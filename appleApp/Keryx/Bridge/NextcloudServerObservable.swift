import Foundation
import KeryxShared
import Observation

/// Mirrors `NextcloudServerController.state` as an `@Observable` property, the same pattern
/// `AddFeedObservable` uses. One instance per sheet presentation.
@MainActor
@Observable
final class NextcloudServerObservable {
    let controller: NextcloudServerController
    private(set) var state: NextcloudServerState

    init(controller: NextcloudServerController) {
        self.controller = controller
        self.state = controller.state.value
    }

    func startObserving() async {
        for await v in controller.state {
            state = v
        }
    }
}

/// A request to ask for a Nextcloud server: the address the field starts from, and what to do once
/// the address passed (connect, switch or reconnect). Presented through `.sheet(item:)`.
struct NextcloudServerPrompt: Identifiable {
    let id = UUID()
    let initialUrl: String
    let onConfirmed: (CloudConnectRequest) -> Void
}
