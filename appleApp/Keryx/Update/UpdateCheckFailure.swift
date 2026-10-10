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
    /// build has not attached its `appcast.xml` yet, or whose macOS job failed. A URL loading error
    /// other than a connectivity one (a bad server response, say) lands here too.
    case unavailable

    /// Classifies `error`, or returns nil when Sparkle's own message should stand.
    /// `downloadErrorDomain`/`downloadErrorCode` are Sparkle's `SUSparkleErrorDomain` and
    /// `SUDownloadError`, passed in by `AppUpdater` so this file needs no Sparkle import.
    ///
    /// Only a download error before any update was found qualifies: once one has been found, the
    /// same error code means the update file itself failed to download, which this message would
    /// misdescribe.
    static func classify(
        _ error: NSError, updateFound: Bool, downloadErrorDomain: String, downloadErrorCode: Int
    ) -> UpdateCheckFailure? {
        guard !updateFound,
              error.domain == downloadErrorDomain,
              error.code == downloadErrorCode
        else { return nil }
        let unreachable = underlyingChain(of: error).contains {
            $0.domain == NSURLErrorDomain && offlineURLErrorCodes.contains($0.code)
        }
        return unreachable ? .offline : .unavailable
    }

    /// The errors under `error`, nearest first. Sparkle nests the URL loading error several
    /// download errors deep, so the immediate underlying error alone does not reveal it.
    private static func underlyingChain(of error: NSError) -> [NSError] {
        var chain: [NSError] = []
        var next = error.userInfo[NSUnderlyingErrorKey] as? NSError
        while let current = next, chain.count < maxUnderlyingDepth {
            chain.append(current)
            next = current.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return chain
    }

    /// The URL loading errors that mean the server could not be reached at all. Any other one (a bad
    /// server response, a TLS failure) is not something checking the connection would fix.
    private static let offlineURLErrorCodes: Set<Int> = [
        NSURLErrorNotConnectedToInternet,
        NSURLErrorCannotFindHost,
        NSURLErrorCannotConnectToHost,
        NSURLErrorNetworkConnectionLost,
        NSURLErrorDNSLookupFailed,
        NSURLErrorTimedOut,
        NSURLErrorInternationalRoamingOff,
        NSURLErrorDataNotAllowed,
    ]

    /// Bounds the walk in case an error ever lists itself among its own underlying errors.
    private static let maxUnderlyingDepth = 16
}
