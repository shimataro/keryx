package works.merc.keryx.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FlatTogglesTest {

    @Test
    fun flatSwitchTogglesOnClick() = runDesktopComposeUiTest {
        var checked by mutableStateOf(false)
        setContent {
            FlatSwitch(checked = checked, onCheckedChange = { checked = it })
        }
        waitForIdle()

        onNode(isToggleable()).assertIsOff()
        onNode(isToggleable()).performClick()
        waitForIdle()
        assertEquals(true, checked)
        onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun flatCheckboxTogglesOnClick() = runDesktopComposeUiTest {
        var checked by mutableStateOf(false)
        setContent {
            FlatCheckbox(checked = checked, onCheckedChange = { checked = it })
        }
        waitForIdle()

        onNode(isToggleable()).assertIsOff()
        onNode(isToggleable()).performClick()
        waitForIdle()
        assertEquals(true, checked)
        onNode(isToggleable()).assertIsOn()
    }
}
