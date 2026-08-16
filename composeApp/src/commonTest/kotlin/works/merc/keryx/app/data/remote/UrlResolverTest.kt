package works.merc.keryx.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlResolverTest {
    @Test
    fun keepsAbsoluteUrls() {
        assertEquals("https://a.com/x", UrlResolver.resolve("https://b.com/", "https://a.com/x"))
    }

    @Test
    fun resolvesRootRelative() {
        assertEquals("https://b.com/feed", UrlResolver.resolve("https://b.com/blog/post", "/feed"))
    }

    @Test
    fun resolvesDocumentRelative() {
        assertEquals("https://b.com/blog/feed.xml", UrlResolver.resolve("https://b.com/blog/post", "feed.xml"))
    }

    @Test
    fun resolvesProtocolRelative() {
        assertEquals("https://cdn.com/i.png", UrlResolver.resolve("https://b.com/", "//cdn.com/i.png"))
    }

    @Test
    fun resolvesParentRelativeWithDotSegments() {
        assertEquals("https://example.com/next", UrlResolver.resolve("https://example.com/article/1", "../next"))
    }

    @Test
    fun resolvesTerminalDotSegmentWithTrailingSlash() {
        assertEquals("https://example.com/article/", UrlResolver.resolve("https://example.com/article/1", "."))
    }

    @Test
    fun resolvesTerminalDotDotSegmentWithTrailingSlash() {
        assertEquals("https://example.com/a/", UrlResolver.resolve("https://example.com/a/b/c", ".."))
    }

    @Test
    fun resolvesQueryOnlyReferenceAgainstCurrentDocument() {
        assertEquals(
            "https://example.com/article/1?view=full",
            UrlResolver.resolve("https://example.com/article/1", "?view=full"),
        )
    }

    @Test
    fun originStripsPathAndKeepsPort() {
        assertEquals("https://b.com", UrlResolver.origin("https://b.com/blog/post"))
        assertEquals("http://b.com:8080", UrlResolver.origin("http://b.com:8080/x"))
    }

    @Test
    fun returnsNullForBlankRef() {
        assertNull(UrlResolver.resolve("https://b.com/", "   "))
    }

    @Test
    fun originReturnsNullForBlankUrl() {
        assertNull(UrlResolver.origin(""))
    }

    @Test
    fun resolveReturnsNullForRelativeRefWithBlankBase() {
        assertNull(UrlResolver.resolve("", "/foo"))
    }

    @Test
    fun resolveKeepsAbsoluteUrlEvenWithBlankBase() {
        assertEquals("https://a.com/x", UrlResolver.resolve("", "https://a.com/x"))
    }

    @Test
    fun hasSchemeDetectsExplicitScheme() {
        assertEquals(true, UrlResolver.hasScheme("https://example.com/feed"))
        assertEquals(true, UrlResolver.hasScheme("  http://example.com/feed  "))
        assertEquals(false, UrlResolver.hasScheme("example.com/feed"))
    }

    @Test
    fun withDefaultSchemePrependsHttpsWhenMissing() {
        assertEquals("https://example.com/feed", UrlResolver.withDefaultScheme("example.com/feed"))
        assertEquals("https://example.com/feed", UrlResolver.withDefaultScheme("  example.com/feed  "))
        assertEquals("http://example.com/feed", UrlResolver.withDefaultScheme("http://example.com/feed"))
    }
}
