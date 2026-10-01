package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SchemeOfTest {

    @Test
    fun extractsCustomUriScheme() {
        assertEquals("keryx", schemeOf("keryx://oauth2/callback"))
    }

    @Test
    fun extractsReversedGoogleClientIdScheme() {
        assertEquals(
            "com.googleusercontent.apps.NNNN-xxxx",
            schemeOf("com.googleusercontent.apps.NNNN-xxxx:/oauth2redirect"),
        )
    }

    @Test
    fun returnsNullWithNoColon() {
        assertNull(schemeOf("not-a-uri"))
    }

    @Test
    fun returnsNullWhenColonIsFirstCharacter() {
        assertNull(schemeOf(":oauth2/callback"))
    }
}
