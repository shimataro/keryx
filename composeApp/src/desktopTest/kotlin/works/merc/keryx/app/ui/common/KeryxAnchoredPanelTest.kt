package works.merc.keryx.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test

/**
 * Desktop `actual` of [KeryxAnchoredPanel] — see its own KDoc: a bare, anchored, dismissable
 * `Popup` on desktop (a `ModalBottomSheet` on Android, not exercised in these JVM-only tests).
 */
@OptIn(ExperimentalTestApi::class)
class KeryxAnchoredPanelTest {

    @Test
    fun rendersItsContent() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                KeryxAnchoredPanel(onDismissRequest = {}) {
                    Text("Panel content")
                }
            }
        }

        onNodeWithText("Panel content").assertIsDisplayed()
    }
}
