package works.merc.keryx.app.ui.i18n

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getPluralString
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.feed_new_articles
import works.merc.keryx.app.resources.settings_import_failed
import works.merc.keryx.app.resources.settings_import_success
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposeNotificationMessagesTest {

    private val messages = ComposeNotificationMessages()

    @Test
    fun newArticlesResolvesThePluralFormForTheGivenCount() = runTest {
        assertEquals(getPluralString(Res.plurals.feed_new_articles, 1, 1), messages.newArticles(1))
        assertEquals(getPluralString(Res.plurals.feed_new_articles, 3, 3), messages.newArticles(3))
    }

    @Test
    fun opmlImportedReportsOnlyTheAddedCountWhenNothingFailed() = runTest {
        val expected = getPluralString(Res.plurals.settings_import_success, 5, 5)
        assertEquals(expected, messages.opmlImported(added = 5, failed = 0))
    }

    @Test
    fun opmlImportedAppendsTheFailedCountWhenSomeImportsFailed() = runTest {
        val addedText = getPluralString(Res.plurals.settings_import_success, 2, 2)
        val failedText = getPluralString(Res.plurals.settings_import_failed, 1, 1)
        assertEquals("$addedText / $failedText", messages.opmlImported(added = 2, failed = 1))
    }
}
