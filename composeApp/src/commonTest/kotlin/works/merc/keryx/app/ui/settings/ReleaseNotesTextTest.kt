package works.merc.keryx.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesTextTest {

    @Test
    fun stripsHeadingMarkers() {
        assertEquals("What's New", plainTextReleaseNotes("## What's New"))
        assertEquals("Title", plainTextReleaseNotes("# Title"))
        assertEquals("Deep", plainTextReleaseNotes("###### Deep"))
    }

    @Test
    fun stripsBoldMarkers() {
        assertEquals("This is bold text", plainTextReleaseNotes("This is **bold** text"))
        assertEquals("Also bold", plainTextReleaseNotes("__Also bold__"))
    }

    @Test
    fun stripsItalicStarMarkersWithoutTouchingBullets() {
        assertEquals("This is italic", plainTextReleaseNotes("This is *italic*"))
    }

    @Test
    fun keepsListItemBulletsIntact() {
        assertEquals("- swipe navigation", plainTextReleaseNotes("- swipe navigation"))
        assertEquals("* lower search minimum", plainTextReleaseNotes("* lower search minimum"))
    }

    @Test
    fun keepsBlankLinesBetweenParagraphs() {
        val input = "First paragraph.\n\nSecond paragraph."
        assertEquals(input, plainTextReleaseNotes(input))
    }

    @Test
    fun handlesARealisticReleaseBody() {
        val input = """
            ## What's Changed
            - Add article swipe navigation
            - **Lower** the minimum search term length to 2 characters

            Full Changelog: https://github.com/owner/repo/compare/v1..v2
        """.trimIndent()
        val expected = """
            What's Changed
            - Add article swipe navigation
            - Lower the minimum search term length to 2 characters

            Full Changelog: https://github.com/owner/repo/compare/v1..v2
        """.trimIndent()
        assertEquals(expected, plainTextReleaseNotes(input))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", plainTextReleaseNotes(""))
    }
}
