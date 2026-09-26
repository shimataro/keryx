package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HtmlTextTest {
    @Test
    fun stripsTagsAndKeepsVisibleText() {
        assertEquals("Hello world", HtmlText.toPlainText("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun dropsTagNamesAndAttributes() {
        val result = HtmlText.toPlainText("<div class=\"post\"><a href=\"https://x\">link</a></div>")
        assertEquals("link", result)
        assertFalse(result.contains("div"))
        assertFalse(result.contains("class"))
        assertFalse(result.contains("href"))
    }

    @Test
    fun plainTextInputIsPreserved() {
        assertEquals("just plain text", HtmlText.toPlainText("just plain text"))
    }

    @Test
    fun emptyInputYieldsEmpty() {
        assertEquals("", HtmlText.toPlainText(""))
    }
}
