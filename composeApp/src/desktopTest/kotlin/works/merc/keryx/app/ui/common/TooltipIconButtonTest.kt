package works.merc.keryx.app.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test for the desktop `actual`'s `when (kind)` container branch added for [IconButtonKind]
 * — one render per [IconButtonKind] value, confirming a click reaches [onClick] when enabled and
 * is swallowed when disabled — plus a check that a disabled button's tooltip still appears on
 * hover. Not a visual/color assertion.
 */
@OptIn(ExperimentalTestApi::class)
class TooltipIconButtonTest {

    @Test
    fun everyKindDeliversClicksWhenEnabled() {
        for (kind in IconButtonKind.entries) {
            runDesktopComposeUiTest {
                var clicks = 0
                setContent {
                    TooltipIconButton(
                        tooltip = "action",
                        onClick = { clicks++ },
                        enabled = true,
                        kind = kind,
                    ) {
                        KeryxIcon(KeryxIcons.Delete, contentDescription = "action")
                    }
                }
                waitForIdle()

                onNodeWithContentDescription("action").performClick()
                waitForIdle()

                assertEquals(1, clicks, "kind=$kind should deliver the click when enabled")
            }
        }
    }

    @Test
    fun everyKindSwallowsClicksWhenDisabled() {
        for (kind in IconButtonKind.entries) {
            runDesktopComposeUiTest {
                var clicks = 0
                setContent {
                    TooltipIconButton(
                        tooltip = "action",
                        onClick = { clicks++ },
                        enabled = false,
                        kind = kind,
                    ) {
                        KeryxIcon(KeryxIcons.Delete, contentDescription = "action")
                    }
                }
                waitForIdle()

                onNodeWithContentDescription("action").performClick()
                waitForIdle()

                assertEquals(0, clicks, "kind=$kind should not deliver the click when disabled")
            }
        }
    }

    /** A disabled button's tooltip can carry why it is disabled (the Sync button's expired sign-in). */
    @Test
    fun disabledButtonStillShowsItsTooltipOnHover() {
        runDesktopComposeUiTest {
            setContent {
                TooltipIconButton(tooltip = "why disabled", onClick = {}, enabled = false) {
                    KeryxIcon(KeryxIcons.Delete, contentDescription = "action")
                }
            }
            waitForIdle()
            onNodeWithText("why disabled").assertDoesNotExist()

            onNodeWithContentDescription("action").performMouseInput {
                moveTo(Offset(width / 2f, height / 2f))
            }
            mainClock.advanceTimeBy(TOOLTIP_SETTLE_MS)
            waitForIdle()

            onNodeWithText("why disabled").assertExists()
        }
    }

    private companion object {
        const val TOOLTIP_SETTLE_MS = 2_000L
    }
}
