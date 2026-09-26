package works.merc.keryx.app.ui.i18n

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getPluralString
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.feed_new_articles
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeNotificationMessagesTest {

    private val messages = ComposeNotificationMessages()

    @Test
    fun newArticlesResolvesThePluralFormForTheGivenCount() = runTest {
        assertEquals(getPluralString(Res.plurals.feed_new_articles, 1, 1), messages.newArticles(1))
        assertEquals(getPluralString(Res.plurals.feed_new_articles, 3, 3), messages.newArticles(3))
    }
}
