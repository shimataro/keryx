package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UntrustedTextTest {
    @Test
    fun leavesOrdinaryTextAlone() {
        assertEquals("Extracted app reports version 9.9.9", untrustedText("Extracted app reports version 9.9.9", 300))
    }

    @Test
    fun dropsControlCharactersSoALogLineCannotBeForged() {
        val forged = untrustedText("9.9.9\n2026-01-01T00:00:00Z INFO [Main] forged", 300)

        assertFalse(forged.contains('\n'), "a newline must never survive into a log line: $forged")
        assertEquals("9.9.92026-01-01T00:00:00Z INFO [Main] forged", forged)
    }

    @Test
    fun boundsTheResultRegardlessOfInputLength() {
        assertEquals(64, untrustedText("A".repeat(100_000), 64).length)
    }

    // Filtering has to happen before truncating, or a value whose first characters are all control
    // characters would spend the whole budget on nothing and report an empty reason.
    @Test
    fun spendsItsBudgetOnVisibleCharactersOnly() {
        val text = "\n".repeat(500) + "the actual reason"

        assertEquals("the actual reason", untrustedText(text, 64))
    }

    @Test
    fun anEmptyOrFullyControlInputYieldsEmpty() {
        assertTrue(untrustedText("", 64).isEmpty())
        assertTrue(untrustedText("\n\r\t", 64).isEmpty())
        // Whitespace is not a control character and is deliberately kept: the point is log-line
        // integrity, not trimming.
        assertEquals(" ", untrustedText(" ", 64))
    }
}
