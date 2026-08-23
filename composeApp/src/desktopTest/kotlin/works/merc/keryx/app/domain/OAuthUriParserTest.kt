package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthUriParserTest {

    @Test
    fun parseAuthorizationCodeAndState() {
        val params = parseOAuthUri("keryx://oauth2/callback?code=abc123&state=xyz789")

        assertEquals("abc123", params.code)
        assertEquals("xyz789", params.state)
        assertNull(params.error)
    }

    @Test
    fun parseErrorResponse() {
        val params = parseOAuthUri("keryx://oauth2/callback?error=access_denied&state=xyz789")

        assertNull(params.code)
        assertEquals("xyz789", params.state)
        assertEquals("access_denied", params.error)
    }

    @Test
    fun parseNoQueryParams() {
        val params = parseOAuthUri("keryx://oauth2/callback")

        assertNull(params.code)
        assertNull(params.state)
        assertNull(params.error)
    }

    @Test
    fun parseOnlyCode() {
        val params = parseOAuthUri("keryx://oauth2/callback?code=onlycode")

        assertEquals("onlycode", params.code)
        assertNull(params.state)
        assertNull(params.error)
    }

    @Test
    fun encodedPlusSignSurvivesAsALiteralPlus() {
        // A raw `+` in the query would itself mean space, so a value containing a literal `+`
        // arrives percent-encoded as `%2B`. Decoding via uri.query (which already percent-decodes)
        // and then URLDecoder.decode would double-decode it into a space instead.
        val params = parseOAuthUri("keryx://oauth2/callback?code=abc%2Bdef")

        assertEquals("abc+def", params.code)
    }
}
