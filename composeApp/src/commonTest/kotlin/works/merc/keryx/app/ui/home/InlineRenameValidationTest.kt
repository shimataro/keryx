package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [inlineRenameValidation], the rule the inline row editor shares with
 * `TextPromptDialog`. The distinction these pin down — "blank" blocks the commit but is *not* an
 * error, while `blockingError` both blocks the commit and marks the field — is invisible from the
 * UI tests in `FeedListInlineRenameTest`, which can only observe that a commit didn't happen.
 */
class InlineRenameValidationTest {

    private val duplicate: (String) -> String? = { name -> if (name == "taken") "duplicate" else null }

    @Test
    fun aBlankValueBlocksTheCommitWithoutBeingAnError() {
        val result = inlineRenameValidation("   ", allowBlank = false, blockingError = duplicate)
        assertNull(result.error, "a blank value must not paint the field red")
        assertFalse(result.canCommit)
    }

    @Test
    fun aBlankValueIsCommittableWhenBlankIsMeaningful() {
        // A feed title: blank means "clear custom_title and fall back to the feed's own title".
        val result = inlineRenameValidation("  ", allowBlank = true, blockingError = { null })
        assertNull(result.error)
        assertTrue(result.canCommit)
    }

    @Test
    fun aBlockingErrorBothMarksTheFieldAndBlocksTheCommit() {
        val result = inlineRenameValidation(" taken ", allowBlank = false, blockingError = duplicate)
        assertEquals("duplicate", result.error)
        assertFalse(result.canCommit)
    }

    @Test
    fun aValidValueCommits() {
        val result = inlineRenameValidation("  free  ", allowBlank = false, blockingError = duplicate)
        assertNull(result.error)
        assertTrue(result.canCommit)
    }

    @Test
    fun blockingErrorIsNotConsultedForAnUncommittableBlankValue() {
        // Mirrors TextPromptDialog: a duplicate-name check would be nonsense against "", and a row
        // the user has merely cleared must not flash red before they have typed anything.
        var calls = 0
        val result = inlineRenameValidation("", allowBlank = false, blockingError = { calls++; "boom" })
        assertEquals(0, calls)
        assertNull(result.error)
        assertFalse(result.canCommit)
    }
}
