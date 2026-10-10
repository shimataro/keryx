import Foundation

/// Why an update check could not even read the update feed, as far as the user can act on it.
///
/// Sparkle reports every such failure as one generic "error occurred in retrieving update
/// information"; `AppUpdater`'s user driver swaps in a message that says what to do. Foundation
/// only, so the logic is testable without Sparkle (`KeryxTests` does not link it).
enum UpdateCheckFailure: Equatable {
    /// The device could not reach the server (offline, DNS, timeout).
    case offline
    /// The server answered with an error, typically a 404: a release just published whose macOS
    /// build has not attached its `appcast.xml` yet, or whose macOS job failed.
    case unavailable

    /// Mirrors `SUSparkleErrorDomain` and `SUDownloadError` from Sparkle's `SUErrors.h`;
    /// `AppUpdater` asserts that they still match.
    static let sparkleErrorDomain = "SUSparkleErrorDomain"
    static let sparkleDownloadErrorCode = 2001

    /// Classifies `error`, or returns nil when Sparkle's own message should stand.
    ///
    /// Only a download error before any update was found qualifies: once one has been found, the
    /// same error code means the update file itself failed to download, which this message would
    /// misdescribe.
    static func classify(_ error: NSError, updateFound: Bool) -> UpdateCheckFailure? {
        guard !updateFound,
              error.domain == sparkleErrorDomain,
              error.code == sparkleDownloadErrorCode
        else { return nil }
        let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError
        return underlying?.domain == NSURLErrorDomain ? .offline : .unavailable
    }
}
