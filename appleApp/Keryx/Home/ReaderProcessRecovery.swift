import Foundation

/// Decides whether the reader reloads its document after WebKit's WebContent process was
/// terminated — kept free of WebKit so the standalone `KeryxTests` bundle can compile it directly.
///
/// iOS may kill that process while the app is in the background (e.g. while the external browser a
/// link opened is in front), leaving the `WKWebView` blank. The reader then reloads the document it
/// holds, but only once per document: a second termination gives up rather than reloading again,
/// even if the reload committed in between, so a page that takes WebContent down (before or after
/// committing) cannot loop forever. Only a different document, or the app becoming active again,
/// earns a new reload — and a document given up on is loaded once more at that activation, since the
/// usual termination happens while the app is in the background and the reader would otherwise stay
/// blank when the user comes back.
@MainActor
final class ReaderProcessRecovery {
    enum TerminationAction: Equatable {
        /// Load the held document again.
        case reload
        /// Leave the reader blank: this document already had its reload.
        case giveUp
    }

    /// Whether the current document has already been reloaded after a termination.
    private var reloadSpent = false
    /// Whether the reader gave up on the current document and is showing nothing.
    private var gaveUp = false

    /// Called when the WebContent process was terminated: what to do with the held document.
    func processTerminated() -> TerminationAction {
        guard !reloadSpent else {
            gaveUp = true
            return .giveUp
        }
        reloadSpent = true
        return .reload
    }

    /// The reader is about to load a document because its content changed, not to recover.
    func documentReplaced() {
        reloadSpent = false
        gaveUp = false
    }

    /// Called when the app became active: whether to load the document given up on once more.
    /// Either way the document earns a fresh reload for the next termination.
    func appBecameActive() -> Bool {
        defer {
            reloadSpent = false
            gaveUp = false
        }
        return gaveUp
    }
}
