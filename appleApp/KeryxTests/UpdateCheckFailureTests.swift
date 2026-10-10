import Foundation
import Testing

@Suite
struct UpdateCheckFailureTests {
    private let domain = "SUSparkleErrorDomain"
    private let download = 2001

    private func classify(_ error: NSError, updateFound: Bool) -> UpdateCheckFailure? {
        UpdateCheckFailure.classify(
            error, updateFound: updateFound, downloadErrorDomain: domain, downloadErrorCode: download
        )
    }

    private func error(code: Int, domain: String? = nil, underlying: NSError? = nil) -> NSError {
        let info: [String: Any] = underlying.map { [NSUnderlyingErrorKey: $0] } ?? [:]
        return NSError(domain: domain ?? self.domain, code: code, userInfo: info)
    }

    @Test
    func unreachableServerIsOffline() {
        let underlying = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        let failure = classify(error(code: download, underlying: underlying), updateFound: false)
        #expect(failure == .offline)
    }

    /// The shape Sparkle actually reports: the URL loading error sits under several download errors.
    @Test
    func unreachableServerNestedUnderDownloadErrorsIsOffline() {
        let url = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotConnectToHost)
        let nested = error(code: download, underlying: error(code: download, underlying: error(code: download, underlying: url)))
        #expect(classify(nested, updateFound: false) == .offline)
    }

    @Test
    func notFoundNestedUnderDownloadErrorsIsUnavailable() {
        let nested = error(code: download, underlying: error(code: download, underlying: error(code: download)))
        #expect(classify(nested, updateFound: false) == .unavailable)
    }

    @Test
    func serverErrorIsUnavailable() {
        let underlying = NSError(domain: domain, code: download)
        let failure = classify(error(code: download, underlying: underlying), updateFound: false)
        #expect(failure == .unavailable)
    }

    @Test
    func downloadErrorWithoutUnderlyingErrorIsUnavailable() {
        #expect(classify(error(code: download), updateFound: false) == .unavailable)
    }

    @Test
    func failureAfterAnUpdateWasFoundKeepsSparklesMessage() {
        #expect(classify(error(code: download), updateFound: true) == nil)
    }

    @Test
    func otherSparkleErrorsKeepSparklesMessage() {
        let appcastParseError = 1000
        #expect(classify(error(code: appcastParseError), updateFound: false) == nil)
    }

    @Test
    func otherDomainsKeepTheirMessage() {
        let other = error(code: download, domain: NSURLErrorDomain)
        #expect(classify(other, updateFound: false) == nil)
    }
}
