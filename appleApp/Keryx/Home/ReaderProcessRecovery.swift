import Foundation

/// Decides whether the reader reloads its document after WebKit's WebContent process was
/// terminated — kept free of WebKit so the standalone `KeryxTests` bundle can compile it directly.
///
/// iOS may kill that process while the app is in the background (e.g. while the external browser a
/// link opened is in front), leaving the `WKWebView` blank. The reader then reloads the document it
/// holds, but only once per commit: a document that takes the process down again before it even
/// commits is not retried, so a page that crashes WebContent cannot loop forever.
@MainActor
final class ReaderProcessRecovery {
    /// Whether a reload after a termination is waiting for its document to commit.
    private var awaitingCommit = false

    /// Called when the WebContent process was terminated: whether to reload the document.
    func shouldReloadAfterTermination() -> Bool {
        guard !awaitingCommit else { return false }
        awaitingCommit = true
        return true
    }

    /// The reader's own document committed: the process is serving it again.
    func documentCommitted() {
        awaitingCommit = false
    }

    /// The reader is about to load a document because its content changed, not to recover.
    func documentReplaced() {
        awaitingCommit = false
    }
}
