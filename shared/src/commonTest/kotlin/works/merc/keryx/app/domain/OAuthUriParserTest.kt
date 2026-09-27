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
        assertNull(params.errorDescription)
    }

    @Test
    fun parseErrorResponseWithDescription() {
        // A realistic Microsoft Identity platform error: percent-encoded space (%20 here, since a
        // raw `+` in error_description text would otherwise be misread as a space by the decoder)
        // and colon, which must come through decoded rather than truncated at the first `:`.
        val params = parseOAuthUri(
            "keryx://oauth2/callback?error=unauthorized_client" +
                "&error_description=AADSTS50011%3A%20The%20redirect%20URI%20specified%20in%20the%20request%20does%20not%20match." +
                "&state=xyz789",
        )

        assertEquals("unauthorized_client", params.error)
        assertEquals(
            "AADSTS50011: The redirect URI specified in the request does not match.",
            params.errorDescription,
        )
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
        // arrives percent-encoded as `%2B`. Percent-decoding twice would turn it into a space.
        val params = parseOAuthUri("keryx://oauth2/callback?code=abc%2Bdef")

        assertEquals("abc+def", params.code)
    }

    @Test
    fun rawPlusSignDecodesAsASpace() {
        val params = parseOAuthUri("keryx://oauth2/callback?error=access_denied&error_description=user+cancelled")

        assertEquals("user cancelled", params.errorDescription)
    }

    @Test
    fun fragmentIsNotPartOfTheLastParameter() {
        val params = parseOAuthUri("keryx://oauth2/callback?code=abc&state=xyz#_=_")

        assertEquals("abc", params.code)
        assertEquals("xyz", params.state)
    }

    @Test
    fun loopbackRequestUriIsParsed() {
        // The Google Drive loopback transport rebuilds a full URI from the request line.
        val params = parseOAuthUri("http://127.0.0.1/?state=s1&code=4%2F0Ab")

        assertEquals("4/0Ab", params.code)
        assertEquals("s1", params.state)
    }
}
