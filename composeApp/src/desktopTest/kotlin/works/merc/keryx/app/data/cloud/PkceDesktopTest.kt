package works.merc.keryx.app.data.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PkceDesktopTest {
    @Test
    fun generateVerifierProducesUrlSafeHighEntropyStrings() {
        val verifiers = (1..100).map { Pkce.generateVerifier() }

        for (verifier in verifiers) {
            assertTrue(verifier.length >= 43, "verifier too short: $verifier")
            assertFalse(verifier.contains('+'), "verifier contains '+': $verifier")
            assertFalse(verifier.contains('/'), "verifier contains '/': $verifier")
            assertFalse(verifier.contains('='), "verifier contains '=': $verifier")
        }

        assertEquals(verifiers.size, verifiers.toSet().size, "expected no collisions across 100 calls")
    }

    @Test
    fun challengeS256MatchesRfc7636AppendixBTestVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        assertEquals(expectedChallenge, Pkce.challengeS256(verifier))
    }

    @Test
    fun challengeS256IsDeterministic() {
        val verifier = Pkce.generateVerifier()

        val challenge1 = Pkce.challengeS256(verifier)
        val challenge2 = Pkce.challengeS256(verifier)

        assertEquals(challenge1, challenge2)
    }
}
