import Foundation
import Testing

@Suite
struct ArticleNavigationPolicyTests {
    private let articleUrl = "https://example.com/posts/1"
    private var outboundLinks: Set<String> { [articleUrl, "https://example.com/other"] }

    @Test
    func readerOwnDocumentLoadIsNotOutbound() {
        // Regression: the reader's own loadHTMLString navigation must never be mistaken for a
        // click on the article's title link.
        let documentUrl = ArticleNavigationPolicy.documentBaseURL ?? URL(string: "about:blank")!
        #expect(!ArticleNavigationPolicy.isOutboundClick(url: documentUrl, outboundLinks: outboundLinks))
    }

    @Test
    func articleAndBodyLinksAreOutbound() {
        #expect(ArticleNavigationPolicy.isOutboundClick(url: URL(string: articleUrl), outboundLinks: outboundLinks))
        #expect(ArticleNavigationPolicy.isOutboundClick(
            url: URL(string: "https://example.com/other"), outboundLinks: outboundLinks
        ))
    }

    @Test
    func missingOrUnknownUrlIsNotOutbound() {
        #expect(!ArticleNavigationPolicy.isOutboundClick(url: nil, outboundLinks: outboundLinks))
        #expect(!ArticleNavigationPolicy.isOutboundClick(
            url: URL(string: "https://platform.twitter.com/embed/Tweet.html"), outboundLinks: outboundLinks
        ))
    }
}
