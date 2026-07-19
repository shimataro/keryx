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
}
