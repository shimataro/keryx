import KeryxShared
import SwiftUI

/// The first real screen: every article's title, across every feed, in one list. Placeholder for
/// the full 3-pane layout (feed list / article list / reader) M2 builds out.
struct HomeView: View {
    var home: HomeObservable

    var body: some View {
        NavigationSplitView {
            List(home.articles, id: \.id) { article in
                Text(article.title)
            }
            .navigationTitle("All Feeds")
        } detail: {
            Text("Select an article")
        }
        .task {
            await home.startObserving()
        }
    }
}
