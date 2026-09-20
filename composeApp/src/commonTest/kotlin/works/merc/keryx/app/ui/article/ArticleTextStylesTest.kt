package works.merc.keryx.app.ui.article

import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleTextStylesTest {
    private fun paragraph() = ArticleBlock.Paragraph(ArticleInline(emptyList()))
    private fun heading(level: Int) = ArticleBlock.Heading(level, ArticleInline(emptyList()))

    @Test
    fun headingFontScaleMatchesUaDefaults() {
        assertEquals(2.0f, headingFontScale(1))
        assertEquals(1.5f, headingFontScale(2))
        assertEquals(1.17f, headingFontScale(3))
        assertEquals(1.0f, headingFontScale(4))
        assertEquals(0.83f, headingFontScale(5))
        assertEquals(0.67f, headingFontScale(6))
    }

    @Test
    fun paragraphMarginIsOneBodyEmOnEachSide() {
        assertEquals(1.0f to 1.0f, blockMarginEm(paragraph()))
    }

    @Test
    fun headingMarginIsConvertedToBodyEm() {
        // h2's own margin is 0.83em of *its own* font-size (1.5em), so in body em it is 0.83*1.5.
        val (top, bottom) = blockMarginEm(heading(2))
        assertEquals(0.83f * 1.5f, top)
        assertEquals(0.83f * 1.5f, bottom)
    }

    @Test
    fun ruleHasAHalfEmMargin() {
        assertEquals(0.5f to 0.5f, blockMarginEm(ArticleBlock.Rule))
    }

    @Test
    fun tableHasNoMargin() {
        assertEquals(0f to 0f, blockMarginEm(ArticleBlock.Table(emptyList())))
    }

    @Test
    fun gapBetweenTwoParagraphsIsOneEm() {
        assertEquals(1.0f, gapBetween(paragraph(), paragraph()))
    }

    @Test
    fun gapCollapsesToTheLargerOfTheTwoAdjacentMargins() {
        // p's bottom margin (1em) vs h2's top margin in body em (0.83*1.5 = 1.245em) -> the larger wins.
        val gap = gapBetween(paragraph(), heading(2))
        assertEquals(0.83f * 1.5f, gap)
    }

    @Test
    fun firstBlockHasNoGapAboveIt() {
        assertEquals(0f, gapBetween(null, paragraph()))
    }
}
