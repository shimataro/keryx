import KeryxShared
import Observation

/// Mirrors `HomeViewModel`'s `StateFlow`s as plain `@Observable` properties, so SwiftUI views read
/// them like any other observable state instead of collecting a `SkieSwiftStateFlow` themselves.
///
/// `@MainActor`: every property write below must land on the actor SwiftUI observes from, and
/// `startObserving()`'s `for await` loop resumes wherever `HomeViewModel`'s own coroutine scope
/// dispatches it — pinning the whole class to `@MainActor` is what makes each resumption hop back
/// here automatically, rather than each loop needing its own explicit `MainActor.run`.
///
/// One `for await` loop per `StateFlow` exposed. This class only carries what the app screens have
/// needed so far; add a property + loop pair here as more of `HomeViewModel`'s state is read.
@MainActor
@Observable
final class HomeObservable {
    private let viewModel: HomeViewModel

    private(set) var articles: [ArticleListRow] = []

    init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
    }

    /// Starts every field's observation loop. Call once from a `.task` on the view that owns this
    /// object; the task's cancellation (the view disappearing) cancels every loop below with it.
    func startObserving() async {
        for await value in viewModel.articles {
            articles = value
        }
    }
}
