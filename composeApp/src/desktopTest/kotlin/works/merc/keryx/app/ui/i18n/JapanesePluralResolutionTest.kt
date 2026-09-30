package works.merc.keryx.app.ui.i18n

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.feed_new_articles
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Japanese has a single CLDR plural category (`other`), so values-ja/strings.xml deliberately
 * defines only `<item quantity="other">`. This pins that a count of 1 still resolves to that
 * Japanese form instead of falling back to the English default's `one` item.
 */
class JapanesePluralResolutionTest {

    private lateinit var originalLocale: Locale

    @BeforeTest
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.JAPANESE)
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun plainStringResolvesToJapanese() = runTest {
        // Guards the premise of the plural test below: the locale switch reaches Compose Resources.
        assertEquals("キャンセル", getString(Res.string.common_cancel))
    }

    @Test
    fun countOfOneResolvesToTheJapaneseOtherForm() = runTest {
        assertEquals("新着記事が 1 件あります", getPluralString(Res.plurals.feed_new_articles, 1, 1))
        assertEquals("新着記事が 3 件あります", getPluralString(Res.plurals.feed_new_articles, 3, 3))
    }
}
