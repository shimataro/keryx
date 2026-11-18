package works.merc.keryx.app.platform.update

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RealCodeSigningVerifier]: each process outcome maps to the right verification result. */
class RealCodeSigningVerifierTest {

    @Test
    fun `a clean exit passes verification`() {
        assertNull(RealCodeSigningVerifier { _, _, _ -> LocalProcessResult.Exited(0) }.verify("/tmp/Keryx.app"))
    }

    @Test
    fun `a non-zero exit reports a failed self-check with the exit code`() {
        val reason = RealCodeSigningVerifier { _, _, _ -> LocalProcessResult.Exited(3) }.verify("/tmp/Keryx.app")

        assertEquals("Extracted app failed its own code-signature self-check (codesign exit 3)", reason)
    }

    @Test
    fun `a timeout is reported as an incomplete check, not a bad signature`() {
        val reason = RealCodeSigningVerifier { _, _, _ -> LocalProcessResult.TimedOut }.verify("/tmp/Keryx.app")

        assertEquals("Could not complete the code-signature check: codesign timed out after 30 seconds", reason)
    }

    @Test
    fun `an interruption is reported as an incomplete check`() {
        val reason = RealCodeSigningVerifier { _, _, _ -> LocalProcessResult.Interrupted }.verify("/tmp/Keryx.app")

        assertEquals("Could not complete the code-signature check: interrupted", reason)
    }

    @Test
    fun `a launch failure is reported with its message`() {
        val reason = RealCodeSigningVerifier { _, _, _ -> throw IOException("No such file") }.verify("/tmp/Keryx.app")

        assertEquals("Could not run the code-signature check: No such file", reason)
    }

    @Test
    fun `codesign is invoked strictly and deeply on the given bundle`() {
        var seen: List<String>? = null
        var seenTimeout: Long? = null
        val verifier = RealCodeSigningVerifier { command, timeout, _ ->
            seen = command
            seenTimeout = timeout
            LocalProcessResult.Exited(0)
        }

        verifier.verify("/Applications/My App.app")

        assertEquals(
            listOf("/usr/bin/codesign", "--verify", "--strict", "--deep", "/Applications/My App.app"),
            seen,
        )
        assertTrue(seenTimeout == 30L)
    }
}
