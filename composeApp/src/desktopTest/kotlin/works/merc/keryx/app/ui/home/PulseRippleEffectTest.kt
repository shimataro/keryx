package works.merc.keryx.app.ui.home

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose-level coverage for `ListRowChrome.kt`'s [PulseRippleEffect] — the shared wiring both
 * [ArticleRow] (see `ArticleRowHighlightTest`'s own `ripplePulse` cases) and the feed-list rows
 * (`FeedListPane.kt`'s `SidebarRow`/`TagRow`/`TagFeedRow`, `FeedListDragAndDrop.kt`'s
 * `FolderGroupHeader`/`FeedRow`) call into. The pure-function/timing coverage of
 * `feedListRipplePulseFor`/`playPulseRipple` itself lives in `FeedListReturnRippleTest` and
 * `ArticleReturnRippleTest`.
 */
@OptIn(ExperimentalTestApi::class)
class PulseRippleEffectTest {

    @Test
    fun aNonzeroPulsePlaysAPressOnTheInteractionSource() = runDesktopComposeUiTest {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        var pulse by mutableStateOf(0)

        setContent {
            LaunchedEffect(Unit) { interactionSource.interactions.collect { interactions += it } }
            PulseRippleEffect(pulse, interactionSource)
        }
        waitForIdle()
        assertEquals(emptyList(), interactions)

        pulse = 1
        waitForIdle()

        // Only the immediate Press is asserted here — playPulseRipple's Release follows a 220ms
        // delay; see ArticleReturnRippleTest's own playPulseRipple test for the deterministic
        // Press-then-Release coverage.
        assertEquals(listOf(PressInteraction.Press::class), interactions.map { it::class })
    }

    @Test
    fun aZeroPulseNeverPlaysAnything() = runDesktopComposeUiTest {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()

        setContent {
            LaunchedEffect(Unit) { interactionSource.interactions.collect { interactions += it } }
            PulseRippleEffect(0, interactionSource)
        }
        waitForIdle()

        assertEquals(emptyList(), interactions)
    }
}
