package works.merc.keryx.app.ui.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ButtonRowLayoutInputs.changedTo] — whether the native button row needs a re-layout. */
class ButtonRowLayoutInputsTest {
    @Test
    fun theFirstCallReportsAChange() {
        assertTrue(ButtonRowLayoutInputs().changedTo("OK", "Cancel"))
    }

    @Test
    fun repeatingTheSameInputsReportsNoChange() {
        val inputs = ButtonRowLayoutInputs()
        inputs.changedTo("OK", "Cancel")

        assertFalse(inputs.changedTo("OK", "Cancel"))
        assertFalse(inputs.changedTo("OK", "Cancel"))
    }

    @Test
    fun aChangedConfirmLabelReportsAChange() {
        val inputs = ButtonRowLayoutInputs()
        inputs.changedTo("OK", "Cancel")

        assertTrue(inputs.changedTo("Save", "Cancel"))
    }

    @Test
    fun aChangedDismissLabelReportsAChange() {
        val inputs = ButtonRowLayoutInputs()
        inputs.changedTo("OK", "Cancel")

        assertTrue(inputs.changedTo("OK", "Close"))
    }

    @Test
    fun hidingAndShowingTheDismissButtonEachReportAChange() {
        val inputs = ButtonRowLayoutInputs()
        inputs.changedTo("OK", "Cancel")

        assertTrue(inputs.changedTo("OK", null))
        assertFalse(inputs.changedTo("OK", null))
        assertTrue(inputs.changedTo("OK", "Cancel"))
    }
}
