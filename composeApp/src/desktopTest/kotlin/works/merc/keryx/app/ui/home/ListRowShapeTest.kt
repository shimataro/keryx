package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop's row geometry and palette — [listRowShape], [rowSelectionColors], and
 * [listRowHorizontalMargin] (see `ListRowChrome.kt`/`ListRowChrome.desktop.kt`'s own KDoc). The
 * `MaterialTheme`-derived values are asserted against one derived independently from the same
 * `MaterialTheme.colorScheme`/`shapes` rather than against a literal, so a future theme change
 * can't silently desync the assertion from the implementation.
 */
@OptIn(ExperimentalTestApi::class)
class ListRowShapeTest {

    @Test
    fun navItemShapeIsMaterialThemeSmallShape() {
        var actual: androidx.compose.ui.graphics.Shape? = null
        var expected: androidx.compose.ui.graphics.Shape? = null
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    expected = MaterialTheme.shapes.small
                    actual = listRowShape(ListRowKind.NavItem)
                }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun listItemShapeIsAlsoMaterialThemeSmallShape() {
        // Desktop deliberately ignores ListRowKind entirely (one shared row style) — see
        // ListRowChrome.desktop.kt's own KDoc.
        var actual: androidx.compose.ui.graphics.Shape? = null
        var expected: androidx.compose.ui.graphics.Shape? = null
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    expected = MaterialTheme.shapes.small
                    actual = listRowShape(ListRowKind.ListItem)
                }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun navItemAndListItemShapesAreTheSameInstance() {
        var navItemShape: androidx.compose.ui.graphics.Shape? = null
        var listItemShape: androidx.compose.ui.graphics.Shape? = null
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    navItemShape = listRowShape(ListRowKind.NavItem)
                    listItemShape = listRowShape(ListRowKind.ListItem)
                }
            }
        }
        assertEquals(navItemShape, listItemShape)
    }

    @Test
    fun rowSelectionColorsIsDerivedFromMaterialThemeColorScheme() {
        var actual: RowSelectionColors? = null
        var expected: RowSelectionColors? = null
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    val primary = MaterialTheme.colorScheme.primary
                    val onPrimary = MaterialTheme.colorScheme.onPrimary
                    expected = RowSelectionColors(
                        focusedBackground = primary,
                        focusedContent = onPrimary,
                        unfocusedBackground = primary.copy(alpha = 0.4f),
                        unfocusedContent = null,
                        echoBackground = primary.copy(alpha = SECONDARY_SELECTION_ALPHA),
                        focusRing = null,
                    )
                    actual = rowSelectionColors()
                }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun rowSelectionColorsUnfocusedBackgroundIsDimmerThanFocusedBackground() {
        var focusedAlpha = 0f
        var unfocusedAlpha = 0f
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    val colors = rowSelectionColors()
                    focusedAlpha = colors.focusedBackground.alpha
                    unfocusedAlpha = colors.unfocusedBackground.alpha
                }
            }
        }
        assertEquals(1f, focusedAlpha)
        assertEquals(0.4f, unfocusedAlpha)
    }

    @Test
    fun rowSelectionColorsUnfocusedContentIsNullOnDesktop() {
        // Desktop's dimmed unfocused background is a mild-enough overlay that each element's own
        // default text/icon color still reads against it — see RowSelectionColors' own KDoc.
        var unfocusedContent: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    unfocusedContent = rowSelectionColors().unfocusedContent
                }
            }
        }
        kotlin.test.assertNull(unfocusedContent)
    }

    @Test
    fun rowSelectionColorsFocusRingIsNullOnDesktop() {
        // Desktop represents pane focus entirely through unfocusedBackground's own dimming, not a
        // separate outline — see RowSelectionColors' own KDoc on focusRing.
        var focusRing: Color? = Color.Unspecified
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    focusRing = rowSelectionColors().focusRing
                }
            }
        }
        kotlin.test.assertNull(focusRing)
    }

    // --- listRowHorizontalMargin (pure function, no composition needed) ---

    @Test
    fun listRowHorizontalMarginIs8dpWhenNotTouchPrimary() {
        assertEquals(8.dp, listRowHorizontalMargin(isTouchPrimary = false))
    }

    @Test
    fun listRowHorizontalMarginIs12dpWhenTouchPrimary() {
        // M3's own NavigationDrawerItemDefaults.ItemPadding (horizontal = 12.dp) — see
        // ListRowChrome.kt's own KDoc.
        assertEquals(12.dp, listRowHorizontalMargin(isTouchPrimary = true))
    }
}
