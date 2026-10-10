import Foundation
import Testing

@Suite
struct UpdateCheckFailureTests {
    private let domain = UpdateCheckFailure.sparkleErrorDomain
    private let download = UpdateCheckFailure.sparkleDownloadErrorCode

    private func error(code: Int, domain: String? = nil, underlying: NSError? = nil) -> NSError {
        let info: [String: Any] = underlying.map { [NSUnderlyingErrorKey: $0] } ?? [:]
        return NSError(domain: domain ?? self.domain, code: code, userInfo: info)
    }

    @Test
    func unreachableServerIsOffline() {
        let underlying = NSError(domain: NSURLErrorDomain, code: NSURLErrorNotConnectedToInternet)
        let failure = UpdateCheckFailure.classify(error(code: download, underlying: underlying), updateFound: false)
        #expect(failure == .offline)
    }

    @Test
    func serverErrorIsUnavailable() {
        let underlying = NSError(domain: domain, code: download)
        let failure = UpdateCheckFailure.classify(error(code: download, underlying: underlying), updateFound: false)
        #expect(failure == .unavailable)
    }

    @Test
    func downloadErrorWithoutUnderlyingErrorIsUnavailable() {
        #expect(UpdateCheckFailure.classify(error(code: download), updateFound: false) == .unavailable)
    }

    @Test
    func failureAfterAnUpdateWasFoundKeepsSparklesMessage() {
        #expect(UpdateCheckFailure.classify(error(code: download), updateFound: true) == nil)
    }

    @Test
    func otherSparkleErrorsKeepSparklesMessage() {
        let appcastParseError = 1000
        #expect(UpdateCheckFailure.classify(error(code: appcastParseError), updateFound: false) == nil)
    }

    @Test
    func otherDomainsKeepTheirMessage() {
        let other = error(code: download, domain: NSURLErrorDomain)
        #expect(UpdateCheckFailure.classify(other, updateFound: false) == nil)
    }
}
