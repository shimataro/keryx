package works.merc.keryx.app.ui.home

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure decisions behind the Android-only (touch-primary) row styling and accessibility in the home
 * panes: the article row's spoken state ([articleRowStateDescription]), the search-hit span
 * ([searchHighlightSpanStyle]), and the notification warning tint ([warningLevelTint]). Desktop
 * must keep its fixed colors, so every platform-gated function is checked on both branches.
 */
class AndroidRowStylingTest {

    private val scheme = lightColorScheme(
        tertiary = Color(0xFF112233),
        tertiaryContainer = Color(0xFF445566),
        onTertiaryContainer = Color(0xFF778899),
    )

    // --- articleRowStateDescription ---

    @Test
    fun aReadUnstarredRowHasNoStateToAnnounce() {
        assertNull(articleRowStateDescription(unread = false, starred = false, unreadLabel = "Unread", starredLabel = "Starred"))
    }

    @Test
    fun anUnreadRowAnnouncesUnread() {
        assertEquals("Unread", articleRowStateDescription(unread = true, starred = false, unreadLabel = "Unread", starredLabel = "Starred"))
    }

    @Test
    fun aStarredRowAnnouncesStarred() {
        assertEquals("Starred", articleRowStateDescription(unread = false, starred = true, unreadLabel = "Unread", starredLabel = "Starred"))
    }

    @Test
    fun anUnreadStarredRowAnnouncesUnreadThenStarred() {
        // Same order and separator as the SwiftUI row's stateAccessibilityValue.
        assertEquals(
            "Unread, Starred",
            articleRowStateDescription(unread = true, starred = true, unreadLabel = "Unread", starredLabel = "Starred"),
        )
    }

    // --- searchHighlightSpanStyle ---

    @Test
    fun desktopKeepsTheFixedHighlighterSpan() {
        assertEquals(SearchHighlightSpanStyle, searchHighlightSpanStyle(isTouchPrimary = false, colorScheme = scheme))
    }

    @Test
    fun touchUsesTheTertiaryContainerPairAndStaysBold() {
        val style = searchHighlightSpanStyle(isTouchPrimary = true, colorScheme = scheme)
        assertEquals(scheme.tertiaryContainer, style.background)
        assertEquals(scheme.onTertiaryContainer, style.color)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }

    @Test
    fun markedTextUsesTheGivenHighlightSpan() {
        val style = searchHighlightSpanStyle(isTouchPrimary = true, colorScheme = scheme)
        val result = markedToAnnotatedString(
            "a${works.merc.keryx.app.data.local.FtsSearch.MARK_START}b${works.merc.keryx.app.data.local.FtsSearch.MARK_END}c",
            style,
        )
        assertEquals("abc", result.text)
        assertEquals(style, result.spanStyles.single().item)
    }

    // --- warningLevelTint ---

    @Test
    fun desktopKeepsTheFixedAmberWarningTint() {
        assertEquals(DesktopWarningTint, warningLevelTint(isTouchPrimary = false, colorScheme = scheme))
    }

    @Test
    fun touchTakesTheWarningTintFromTheColorScheme() {
        assertEquals(scheme.tertiary, warningLevelTint(isTouchPrimary = true, colorScheme = scheme))
    }
}
