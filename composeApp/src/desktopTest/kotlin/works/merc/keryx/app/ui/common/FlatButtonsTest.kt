package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlin.test.Test
import kotlin.test.assertEquals

/** A label whose glyphs (kanji, hiragana, an ellipsis) a host can resolve to different fonts than
 * the Latin one below — see [aFlatButtonsHeightDoesNotDependOnItsLabel]. */
private const val CJK_LABEL = "再起動しています…"

/** `labelLarge`'s 20sp line height plus [FlatButton]'s own 10dp vertical padding, twice. */
private val EXPECTED_BUTTON_HEIGHT = 40.dp

@OptIn(ExperimentalTestApi::class)
class FlatButtonsTest {

    /**
     * A flat button's height must come from its style, not from the fonts the host happens to
     * resolve for the characters in its label. Without a label text style, `TextStyle.Default`
     * leaves `lineHeight` unspecified and the label — hence the button — is as tall as the
     * resolved font's own metrics: the macOS CI runner measured "ダウンロード" at 16dp and
     * [CJK_LABEL] at 20dp, which made the Updates tab's headline row change height between
     * `UpdateState.Available` and `UpdateState.Installing` on that host and nowhere else (see
     * `UpdatesTabTest.theHeadlineRowIsTheSameHeightWithOrWithoutATrailingButton`).
     *
     * The exact-height assertion is what makes this reproducible on a machine that resolves both
     * labels to the same metrics: the equality alone holds there either way.
     */
    @Test
    fun aFlatButtonsHeightDoesNotDependOnItsLabel() = runDesktopComposeUiTest {
        setContent {
            Column {
                FlatButton(onClick = {}, modifier = Modifier.testTag("latin")) { Text("OK") }
                FlatButton(onClick = {}, modifier = Modifier.testTag("cjk")) { Text(CJK_LABEL) }
            }
        }
        waitForIdle()

        val latinHeight = onNodeWithTag("latin").getBoundsInRoot().height
        val cjkHeight = onNodeWithTag("cjk").getBoundsInRoot().height

        assertEquals(latinHeight, cjkHeight)
        assertEquals(EXPECTED_BUTTON_HEIGHT, latinHeight)
    }
}
