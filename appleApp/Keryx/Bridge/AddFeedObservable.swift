import KeryxShared
import Observation

/// Mirrors `AddFeedController.state` as an `@Observable` property, the same pattern
/// `HomeObservable` uses for `HomeViewModel`. One instance per sheet presentation.
@MainActor
@Observable
final class AddFeedObservable {
    let controller: AddFeedController
    private(set) var state: AddFeedState = AddFeedState(
        url: "", phase: nil, preview: nil, selectedCandidates: [], error: nil, partialResult: nil
    )

    init(controller: AddFeedController) {
        self.controller = controller
    }

    func startObserving() async {
        for await v in controller.state {
            state = v
        }
    }
}
