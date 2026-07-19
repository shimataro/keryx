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
    fun originStripsPathAndKeepsPort() {
        assertEquals("https://b.com", UrlResolver.origin("https://b.com/blog/post"))
        assertEquals("http://b.com:8080", UrlResolver.origin("http://b.com:8080/x"))
    }

    @Test
    fun returnsNullForBlankRef() {
        assertNull(UrlResolver.resolve("https://b.com/", "   "))
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
