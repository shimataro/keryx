package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
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
                        selectedBackground = primary,
                        selectedContent = onPrimary,
                        paneFocus = PaneFocusIndication.Dim(0.4f),
                    )
                    actual = rowSelectionColors()
                }
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun rowSelectionColorsPaneFocusIsDimAtFortyPercentOnDesktop() {
        // A single assertion that the mechanism is exactly Dim (never Ring) captures both "pane
        // focus is shown as background dimming" and "there is no separate outline" at once — the
        // two used to be three separate field-by-field assertions (a dimmer unfocusedBackground, a
        // null unfocusedContent, a null focusRing) before RowSelectionColors collapsed those
        // per-platform-redundant fields into this one PaneFocusIndication value. See
        // SelectionBackgroundTest's selectedButNotFocusedIsDimmedPrimary for the derived color this
        // produces through selectionBackground(selected, focused).
        var paneFocus: PaneFocusIndication? = null
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    paneFocus = rowSelectionColors().paneFocus
                }
            }
        }
        assertEquals(PaneFocusIndication.Dim(0.4f), paneFocus)
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
