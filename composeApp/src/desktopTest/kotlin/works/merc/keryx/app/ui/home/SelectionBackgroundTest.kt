package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SelectionBackgroundTest {

    private fun colorFor(selected: Boolean, focused: Boolean): Pair<Color, Color> {
        var actual = Color.Unspecified
        var primary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    primary = MaterialTheme.colorScheme.primary
                    actual = selectionBackground(selected, focused)
                }
            }
        }
        return actual to primary
    }

    private fun contentColorFor(selected: Boolean, focused: Boolean): Pair<Color?, Color> {
        var actual: Color? = Color.Unspecified
        var onPrimary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    onPrimary = MaterialTheme.colorScheme.onPrimary
                    actual = selectionContentColorOrNull(selected, focused)
                }
            }
        }
        return actual to onPrimary
    }

    @Test
    fun selectedAndFocusedIsFullStrengthPrimary() {
        val (actual, primary) = colorFor(selected = true, focused = true)
        assertEquals(primary, actual)
    }

    @Test
    fun selectedButNotFocusedIsDimmedPrimary() {
        val (actual, primary) = colorFor(selected = true, focused = false)
        assertEquals(primary.copy(alpha = 0.4f), actual)
    }

    @Test
    fun notSelectedIsTransparentWhenFocused() {
        assertEquals(Color.Transparent, colorFor(selected = false, focused = true).first)
    }

    @Test
    fun notSelectedIsTransparentWhenNotFocused() {
        assertEquals(Color.Transparent, colorFor(selected = false, focused = false).first)
    }

    @Test
    fun contentColorIsOnPrimaryWhenSelectedAndFocused() {
        val (actual, onPrimary) = contentColorFor(selected = true, focused = true)
        assertEquals(onPrimary, actual)
    }

    @Test
    fun contentColorIsNullWhenSelectedButNotFocused() {
        assertNull(contentColorFor(selected = true, focused = false).first)
    }

    @Test
    fun contentColorIsNullWhenNotSelected() {
        assertNull(contentColorFor(selected = false, focused = true).first)
    }

    // --- tone-aware overloads (rows that can render the same feed more than once) ---

    private fun colorFor(tone: RowSelectionTone, focused: Boolean): Pair<Color, Color> {
        var actual = Color.Unspecified
        var primary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    primary = MaterialTheme.colorScheme.primary
                    actual = selectionBackground(tone, focused)
                }
            }
        }
        return actual to primary
    }

    private fun contentColorFor(tone: RowSelectionTone, focused: Boolean): Pair<Color?, Color> {
        var actual: Color? = Color.Unspecified
        var onPrimary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    onPrimary = MaterialTheme.colorScheme.onPrimary
                    actual = selectionContentColorOrNull(tone, focused)
                }
            }
        }
        return actual to onPrimary
    }

    @Test
    fun primaryToneMatchesTheBooleanSelectedBackgroundInBothFocusStates() {
        // A feed with no tag-nested duplicate must look exactly as it did before tones existed.
        val (focusedColor, primary) = colorFor(RowSelectionTone.PRIMARY, focused = true)
        assertEquals(primary, focusedColor)
        val (unfocusedColor, primaryAgain) = colorFor(RowSelectionTone.PRIMARY, focused = false)
        assertEquals(primaryAgain.copy(alpha = 0.4f), unfocusedColor)
    }

    @Test
    fun secondaryToneIsAFainterPrimaryTintRegardlessOfFocus() {
        val (focusedColor, primary) = colorFor(RowSelectionTone.SECONDARY, focused = true)
        assertEquals(primary.copy(alpha = SECONDARY_SELECTION_ALPHA), focusedColor)
        val (unfocusedColor, primaryAgain) = colorFor(RowSelectionTone.SECONDARY, focused = false)
        assertEquals(primaryAgain.copy(alpha = SECONDARY_SELECTION_ALPHA), unfocusedColor)
    }

    @Test
    fun secondaryToneIsClearlyFainterThanAnUnfocusedPrimaryRow() {
        // The whole point of the tone: a duplicate row must not be mistakable for the focused one.
        assertTrue(SECONDARY_SELECTION_ALPHA < 0.4f)
    }

    @Test
    fun noneToneIsTransparentInBothFocusStates() {
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = true).first)
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = false).first)
    }

    @Test
    fun toneContentColorIsOnPrimaryOnlyForAFocusedPrimaryRow() {
        val (actual, onPrimary) = contentColorFor(RowSelectionTone.PRIMARY, focused = true)
        assertEquals(onPrimary, actual)
        assertNull(contentColorFor(RowSelectionTone.PRIMARY, focused = false).first)
        assertNull(contentColorFor(RowSelectionTone.SECONDARY, focused = true).first)
        assertNull(contentColorFor(RowSelectionTone.SECONDARY, focused = false).first)
        assertNull(contentColorFor(RowSelectionTone.NONE, focused = true).first)
    }

    // --- LocalRowSelectionVisible (PaneLayout.Single suppresses the highlight entirely) ---

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun booleanBackgroundIsTransparentWhenSelectionVisibilityIsSuppressedEvenIfSelectedAndFocused() {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                        actual = selectionBackground(selected = true, focused = true)
                    }
                }
            }
        }
        assertEquals(Color.Transparent, actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun booleanContentColorIsNullWhenSelectionVisibilityIsSuppressedEvenIfSelectedAndFocused() {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                        actual = selectionContentColorOrNull(selected = true, focused = true)
                    }
                }
            }
        }
        assertNull(actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun toneBackgroundIsTransparentWhenSelectionVisibilityIsSuppressedEvenForAFocusedPrimaryRow() {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                        actual = selectionBackground(RowSelectionTone.PRIMARY, focused = true)
                    }
                }
            }
        }
        assertEquals(Color.Transparent, actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun toneContentColorIsNullWhenSelectionVisibilityIsSuppressedEvenForAFocusedPrimaryRow() {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                        actual = selectionContentColorOrNull(RowSelectionTone.PRIMARY, focused = true)
                    }
                }
            }
        }
        assertNull(actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun booleanBackgroundStillHighlightsWhenSelectionVisibilityIsTheDefault() {
        // The default (no provider in the tree, as production HomeScreen leaves it at
        // PaneLayout.Dual/Triple) must behave exactly as before this CompositionLocal existed.
        var actual = Color.Unspecified
        var primary = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    primary = MaterialTheme.colorScheme.primary
                    actual = selectionBackground(selected = true, focused = true)
                }
            }
        }
        assertEquals(primary, actual)
    }

    // --- explicit RowSelectionColors injection (platform-independent branch logic) ---

    /**
     * An arbitrary [RowSelectionColors] shaped like Android's `actual` (see
     * `ListRowChrome.android.kt`'s `rowSelectionColors()`): the focused/unfocused background/content
     * pair collapses to the *same* value regardless of pane focus (M3's `NavigationDrawerItem` itself
     * doesn't recolor on focus) — pane focus is instead carried by a non-null [focusRing]. Distinct
     * from desktop's own palette so a test asserting against this fixture cannot pass by accident
     * against the real desktop `actual`.
     */
    private val androidLikeColors = RowSelectionColors(
        focusedBackground = Color(0xFF112233),
        focusedContent = Color(0xFF445566),
        unfocusedBackground = Color(0xFF112233),
        unfocusedContent = Color(0xFF445566),
        echoBackground = Color(0xFF112233).copy(alpha = SECONDARY_SELECTION_ALPHA),
        focusRing = Color(0xFF778899),
    )

    private fun colorFor(selected: Boolean, focused: Boolean, colors: RowSelectionColors): Color {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent { actual = selectionBackground(selected, focused, colors) }
        }
        return actual
    }

    private fun contentColorFor(selected: Boolean, focused: Boolean, colors: RowSelectionColors): Color? {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent { actual = selectionContentColorOrNull(selected, focused, colors) }
        }
        return actual
    }

    private fun colorFor(tone: RowSelectionTone, focused: Boolean, colors: RowSelectionColors): Color {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent { actual = selectionBackground(tone, focused, colors) }
        }
        return actual
    }

    private fun contentColorFor(tone: RowSelectionTone, focused: Boolean, colors: RowSelectionColors): Color? {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent { actual = selectionContentColorOrNull(tone, focused, colors) }
        }
        return actual
    }

    @Test
    fun explicitColorsBooleanOverloadPicksFocusedBackgroundWhenFocused() {
        assertEquals(
            androidLikeColors.focusedBackground,
            colorFor(selected = true, focused = true, colors = androidLikeColors),
        )
    }

    @Test
    fun explicitColorsBooleanOverloadPicksUnfocusedBackgroundWhenSelectedButNotFocused() {
        assertEquals(
            androidLikeColors.unfocusedBackground,
            colorFor(selected = true, focused = false, colors = androidLikeColors),
        )
    }

    @Test
    fun explicitColorsBooleanOverloadIsTransparentWhenNotSelectedRegardlessOfFocus() {
        assertEquals(Color.Transparent, colorFor(selected = false, focused = true, colors = androidLikeColors))
        assertEquals(Color.Transparent, colorFor(selected = false, focused = false, colors = androidLikeColors))
    }

    @Test
    fun explicitColorsBooleanContentColorMatchesFocusedAndUnfocusedContentRespectively() {
        assertEquals(
            androidLikeColors.focusedContent,
            contentColorFor(selected = true, focused = true, colors = androidLikeColors),
        )
        assertEquals(
            androidLikeColors.unfocusedContent,
            contentColorFor(selected = true, focused = false, colors = androidLikeColors),
        )
    }

    @Test
    fun explicitColorsBooleanContentColorIsNullWhenNotSelected() {
        assertNull(contentColorFor(selected = false, focused = true, colors = androidLikeColors))
    }

    /**
     * The behavior that makes Android's selection look "the same either way": a palette whose
     * [RowSelectionColors.unfocusedContent]/[RowSelectionColors.unfocusedBackground] equal its
     * focused counterparts must produce identical results with `focused` true or false — i.e. a
     * platform without a focus axis genuinely can't tell the two apart. Desktop's own palette
     * (`unfocusedContent = null`, a dimmer `unfocusedBackground`) would fail this same assertion,
     * which is exactly the point: this is Android's behavior, verified without depending on
     * Android's `actual` ever running in this (desktop) test target.
     */
    @Test
    fun aColorsInstanceWithEqualFocusedAndUnfocusedValuesLooksIdenticalRegardlessOfFocus() {
        val focusedBg = colorFor(selected = true, focused = true, colors = androidLikeColors)
        val unfocusedBg = colorFor(selected = true, focused = false, colors = androidLikeColors)
        assertEquals(focusedBg, unfocusedBg)

        val focusedContent = contentColorFor(selected = true, focused = true, colors = androidLikeColors)
        val unfocusedContent = contentColorFor(selected = true, focused = false, colors = androidLikeColors)
        assertEquals(focusedContent, unfocusedContent)
    }

    @Test
    fun explicitColorsToneOverloadPicksEchoBackgroundForSecondaryTone() {
        assertEquals(
            androidLikeColors.echoBackground,
            colorFor(RowSelectionTone.SECONDARY, focused = true, colors = androidLikeColors),
        )
        assertEquals(
            androidLikeColors.echoBackground,
            colorFor(RowSelectionTone.SECONDARY, focused = false, colors = androidLikeColors),
        )
    }

    @Test
    fun explicitColorsToneOverloadIsTransparentForNoneTone() {
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = true, colors = androidLikeColors))
        assertEquals(Color.Transparent, colorFor(RowSelectionTone.NONE, focused = false, colors = androidLikeColors))
    }

    @Test
    fun explicitColorsToneOverloadPicksFocusedOrUnfocusedBackgroundForPrimaryTone() {
        assertEquals(
            androidLikeColors.focusedBackground,
            colorFor(RowSelectionTone.PRIMARY, focused = true, colors = androidLikeColors),
        )
        assertEquals(
            androidLikeColors.unfocusedBackground,
            colorFor(RowSelectionTone.PRIMARY, focused = false, colors = androidLikeColors),
        )
    }

    /**
     * Regression guard for the tone-aware content color now taking [RowSelectionColors.unfocusedContent]
     * instead of always returning `null` for an unfocused [RowSelectionTone.PRIMARY] row — desktop's own
     * `null` unfocusedContent kept the old behavior invisible, so this only shows up with a palette
     * (like Android's) that actually sets one.
     */
    @Test
    fun explicitColorsTonePrimaryContentColorUsesUnfocusedContentWhenNotFocused() {
        assertEquals(
            androidLikeColors.unfocusedContent,
            contentColorFor(RowSelectionTone.PRIMARY, focused = false, colors = androidLikeColors),
        )
    }

    @Test
    fun explicitColorsToneContentColorIsNullForNonPrimaryTones() {
        assertNull(contentColorFor(RowSelectionTone.SECONDARY, focused = true, colors = androidLikeColors))
        assertNull(contentColorFor(RowSelectionTone.NONE, focused = true, colors = androidLikeColors))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun explicitColorsBooleanBackgroundIsTransparentWhenSelectionVisibilityIsSuppressedRegardlessOfColors() {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                    actual = selectionBackground(selected = true, focused = true, colors = androidLikeColors)
                }
            }
        }
        assertEquals(Color.Transparent, actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun explicitColorsBooleanContentColorIsNullWhenSelectionVisibilityIsSuppressedRegardlessOfColors() {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                    actual = selectionContentColorOrNull(selected = true, focused = true, colors = androidLikeColors)
                }
            }
        }
        assertNull(actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun explicitColorsToneBackgroundIsTransparentWhenSelectionVisibilityIsSuppressedRegardlessOfColors() {
        var actual = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                    actual = selectionBackground(RowSelectionTone.PRIMARY, focused = true, colors = androidLikeColors)
                }
            }
        }
        assertEquals(Color.Transparent, actual)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun explicitColorsToneContentColorIsNullWhenSelectionVisibilityIsSuppressedRegardlessOfColors() {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalRowSelectionVisible provides false) {
                    actual = selectionContentColorOrNull(RowSelectionTone.PRIMARY, focused = true, colors = androidLikeColors)
                }
            }
        }
        assertNull(actual)
    }

    // --- rowOutlineColorOrNull (listRowOutline's judgment half) ---

    private fun outlineColorFor(
        selected: Boolean,
        focused: Boolean,
        dropTargetColor: Color? = null,
        colors: RowSelectionColors = androidLikeColors,
        keyboardEngaged: Boolean = true,
        selectionVisible: Boolean = true,
    ): Color? {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalKeyboardEngaged provides keyboardEngaged,
                    LocalRowSelectionVisible provides selectionVisible,
                ) {
                    actual = rowOutlineColorOrNull(selected, focused, dropTargetColor, colors)
                }
            }
        }
        return actual
    }

    private fun outlineColorFor(
        tone: RowSelectionTone,
        focused: Boolean,
        dropTargetColor: Color? = null,
        colors: RowSelectionColors = androidLikeColors,
        keyboardEngaged: Boolean = true,
        selectionVisible: Boolean = true,
    ): Color? {
        var actual: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalKeyboardEngaged provides keyboardEngaged,
                    LocalRowSelectionVisible provides selectionVisible,
                ) {
                    actual = rowOutlineColorOrNull(tone, focused, dropTargetColor, colors)
                }
            }
        }
        return actual
    }

    @Test
    fun outlineBooleanOverloadShowsFocusRingWhenSelectedFocusedAndKeyboardEngaged() {
        assertEquals(androidLikeColors.focusRing, outlineColorFor(selected = true, focused = true))
    }

    @Test
    fun outlineBooleanOverloadIsNullWhenSelectedButNotFocused() {
        assertNull(outlineColorFor(selected = true, focused = false))
    }

    @Test
    fun outlineBooleanOverloadIsNullWhenFocusedButNotSelected() {
        assertNull(outlineColorFor(selected = false, focused = true))
    }

    @Test
    fun outlineBooleanOverloadIsNullWhenKeyboardNotEngagedEvenIfSelectedAndFocused() {
        // A touch-only session (no hardware key ever pressed) must never show a focus ring meant
        // for keyboard navigation — see LocalKeyboardEngaged's own KDoc.
        assertNull(outlineColorFor(selected = true, focused = true, keyboardEngaged = false))
    }

    @Test
    fun outlineBooleanOverloadIsNullWhenSelectionVisibilityIsSuppressedEvenIfSelectedFocusedAndEngaged() {
        assertNull(outlineColorFor(selected = true, focused = true, selectionVisible = false))
    }

    @Test
    fun outlineBooleanOverloadIsNullWhenColorsHasNoFocusRing() {
        // Desktop's own palette (focusRing = null) — represents pane focus through background
        // dimming instead, never a separate outline.
        assertNull(outlineColorFor(selected = true, focused = true, colors = androidLikeColors.copy(focusRing = null)))
    }

    @Test
    fun outlineBooleanOverloadDropTargetColorWinsOverFocusRing() {
        val dropColor = Color(0xFFAABBCC)
        assertEquals(dropColor, outlineColorFor(selected = true, focused = true, dropTargetColor = dropColor))
    }

    @Test
    fun outlineBooleanOverloadDropTargetColorAppliesEvenWhenNotSelected() {
        // A row can be a drop target without being the selected row at all (e.g. NoFolderHeader,
        // which is never selectable) — the drop-target outline is independent of selection.
        val dropColor = Color(0xFFAABBCC)
        assertEquals(dropColor, outlineColorFor(selected = false, focused = false, dropTargetColor = dropColor))
    }

    @Test
    fun outlineToneOverloadShowsFocusRingOnlyForPrimaryToneWhenFocusedAndEngaged() {
        assertEquals(androidLikeColors.focusRing, outlineColorFor(RowSelectionTone.PRIMARY, focused = true))
        assertNull(outlineColorFor(RowSelectionTone.SECONDARY, focused = true))
        assertNull(outlineColorFor(RowSelectionTone.NONE, focused = true))
    }

    @Test
    fun outlineToneOverloadIsNullWhenPrimaryButNotFocused() {
        assertNull(outlineColorFor(RowSelectionTone.PRIMARY, focused = false))
    }

    @Test
    fun outlineToneOverloadIsNullWhenKeyboardNotEngagedEvenIfPrimaryAndFocused() {
        assertNull(outlineColorFor(RowSelectionTone.PRIMARY, focused = true, keyboardEngaged = false))
    }

    @Test
    fun outlineToneOverloadDropTargetColorWinsOverFocusRing() {
        val dropColor = Color(0xFFAABBCC)
        assertEquals(dropColor, outlineColorFor(RowSelectionTone.PRIMARY, focused = true, dropTargetColor = dropColor))
    }
}
