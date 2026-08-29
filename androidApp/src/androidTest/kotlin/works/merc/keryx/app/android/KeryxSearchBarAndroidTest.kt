package works.merc.keryx.app.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import works.merc.keryx.app.ui.common.KeryxCollapsedSearchBar
import works.merc.keryx.app.ui.common.KeryxExpandedSearchBar

/**
 * Regression coverage for the Android `KeryxSearchBar` actuals — the M3-backed search entry point
 * and search-screen header introduced to fix the narrow-layout search bug (see `FeedListPane.kt`'s
 * own KDoc). Lives in androidApp for the same reason [NativeMenuAndroidGestureTest] does — Compose
 * Multiplatform's Android instrumented runner needs a real Android application module to host
 * [createComposeRule]; `composeApp` itself is an Android library, not an application.
 */
class KeryxSearchBarAndroidTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val roleButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun collapsedBarExposesRoleButtonAndNoTextInput() {
        composeTestRule.setContent {
            MaterialTheme {
                KeryxCollapsedSearchBar(
                    text = "Search articles…",
                    isPlaceholder = true,
                    onClick = {},
                    onClickLabel = "Search articles",
                    modifier = Modifier.testTag("collapsed-bar"),
                )
            }
        }

        // A read-only entry point must never expose SetText — see KeryxCollapsedSearchBar's own
        // KDoc on why it is not a read-only text field.
        composeTestRule.onNodeWithTag("collapsed-bar").assert(roleButton) {
            "expected the collapsed bar to carry Role.Button"
        }
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun collapsedBarMeetsTheMaterialTouchTargetMinimum() {
        composeTestRule.setContent {
            MaterialTheme {
                KeryxCollapsedSearchBar(
                    text = "Search articles…",
                    isPlaceholder = true,
                    onClick = {},
                    onClickLabel = "Search articles",
                    modifier = Modifier.testTag("collapsed-bar").width(300.dp),
                )
            }
        }

        composeTestRule.onNodeWithTag("collapsed-bar").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun collapsedBarClickInvokesOnClick() {
        var clicked = false
        composeTestRule.setContent {
            MaterialTheme {
                KeryxCollapsedSearchBar(
                    text = "Search articles…",
                    isPlaceholder = true,
                    onClick = { clicked = true },
                    onClickLabel = "Search articles",
                )
            }
        }

        composeTestRule.onNodeWithText("Search articles…").performClick()

        assertTrue(clicked, "expected onClick to have fired")
    }

    @Test
    fun expandedBarFieldAcceptsTextInputWhileTheBackArrowStaysClickable() {
        var reported: String? = null
        var backClicked = false
        composeTestRule.setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "kotlin",
                    onQueryChange = { reported = it },
                    placeholder = "Search articles…",
                    onNavigateUp = { backClicked = true },
                    navigateUpEnabled = true,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("x")
        composeTestRule.waitForIdle()
        assertEquals("kotlinx", reported)

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backClicked, "expected the back arrow to still be clickable beside the field")
    }

    /**
     * The one risk `desktopTest` cannot cover (see the plan's own note): M3's `TopAppBar` has a
     * fixed 64dp container height, but this app's font-size setting scales text up to 1.4× — at
     * that scale the field's own minimum height must still grow past 56dp without clipping, which
     * is only true because [KeryxExpandedSearchBar] is a plain pill, not a fixed-height app bar.
     */
    @Test
    fun expandedBarFieldIsNotClippedAtTheLargestFontSizeSetting() {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.4f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        KeryxExpandedSearchBar(
                            query = "kotlin",
                            onQueryChange = {},
                            placeholder = "Search articles…",
                            onNavigateUp = {},
                            navigateUpEnabled = true,
                            navigateUpContentDescription = "Back",
                            clearContentDescription = "Clear",
                            onSearchAction = {},
                            modifier = Modifier.testTag("expanded-bar"),
                        )
                    }
                }
            }
        }

        val barBounds = composeTestRule.onNodeWithTag("expanded-bar").getBoundsInRoot()
        val fieldBounds = composeTestRule.onNode(hasSetTextAction()).getBoundsInRoot()
        val barHeight = barBounds.bottom - barBounds.top
        val fieldHeight = fieldBounds.bottom - fieldBounds.top
        assertTrue(
            fieldHeight <= barHeight,
            "expected the field ($fieldHeight) to fit within the bar ($barHeight) at 1.4x font scale",
        )
    }

    @Test
    fun expandedBarNavigateUpIsDisabledWhenNotEnabled() {
        composeTestRule.setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "",
                    onQueryChange = {},
                    placeholder = "Search articles…",
                    onNavigateUp = {},
                    navigateUpEnabled = false,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back").assertIsNotEnabled()
    }
}
