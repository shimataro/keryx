package works.merc.keryx.app.ui.home

import io.github.kdroidfilter.webview.web.LoadingState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `shouldLoadArticleHtml` decides whether the article reader's WebView needs a fresh
 * `navigator.loadHtml(...)` push. It is compared by document rather than by article id because
 * the placeholder and "no content" states share the same WebView and have no article id.
 */
class ArticleDetailLoadGuardTest {
    @Test
    fun neverLoadsWhileInitializing() {
        assertFalse(shouldLoadArticleHtml(LoadingState.Initializing, loadedHtml = null, html = "<html>a</html>"))
    }

    @Test
    fun loadsANewDocumentWhileAnotherIsStillLoading() {
        // The article on screen can keep the view loading indefinitely (its script setting
        // `location`); the newer document must still be pushed.
        assertTrue(shouldLoadArticleHtml(LoadingState.Loading(0.5f), loadedHtml = "<html>a</html>", html = "<html>b</html>"))
    }

    @Test
    fun skipsAnIdenticalRepeatWhileLoading() {
        assertFalse(shouldLoadArticleHtml(LoadingState.Loading(0.5f), loadedHtml = "<html>a</html>", html = "<html>a</html>"))
    }

    @Test
    fun loadsTheFirstDocumentOnceFinished() {
        assertTrue(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = null, html = "<html>a</html>"))
    }

    @Test
    fun skipsAnIdenticalRepeat() {
        assertFalse(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = "<html>a</html>", html = "<html>a</html>"))
    }

    @Test
    fun loadsWhenTheThemeChangesForTheSameArticle() {
        // Same article, different rendered document (e.g. a light/dark theme switch) — must reload.
        assertTrue(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = "<html>light</html>", html = "<html>dark</html>"))
    }

    @Test
    fun loadsGoingFromPlaceholderToArticle() {
        assertTrue(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = "<html>placeholder</html>", html = "<html>article</html>"))
    }

    @Test
    fun loadsGoingFromArticleToPlaceholder() {
        assertTrue(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = "<html>article</html>", html = "<html>placeholder</html>"))
    }

    @Test
    fun loadsSwitchingBetweenTwoDifferentArticles() {
        assertTrue(shouldLoadArticleHtml(LoadingState.Finished, loadedHtml = "<html>article-a</html>", html = "<html>article-b</html>"))
    }

    @Test
    fun aMarkedDocumentCarriesItsMarkerInItsHead() {
        val html = "<html><head><style></style></head><body>a</body></html>"
        val marked = markReaderDocument(html, marker = "7")
        assertTrue(marked.startsWith("<html><head><meta name=\"keryx-document\" content=\"7\">"))
        assertTrue(marked.endsWith("<style></style></head><body>a</body></html>"))
    }

    @Test
    fun aDocumentWithoutAHeadIsStillMarked() {
        assertTrue(markReaderDocument("<p>a</p>", marker = "1").startsWith("<meta name=\"keryx-document\""))
    }

    @Test
    fun eachPushGetsItsOwnMarker() {
        assertNotEquals(readerDocumentMarker(1), readerDocumentMarker(2))
    }

    @Test
    fun theProbedMarkerIsRecognizedWhetherOrNotThePlatformQuotesIt() {
        val marker = readerDocumentMarker(3)
        assertTrue(isReaderDocumentOnScreen("\"$marker\"", marker))
        assertTrue(isReaderDocumentOnScreen(marker, marker))
    }

    @Test
    fun anotherPushOrNoneIsNotOnScreen() {
        // An older push of the very same HTML is still another push: identity never comes from the
        // document's content, so a newer document cannot be taken for an older one whose hash matches.
        val marker = readerDocumentMarker(3)
        assertFalse(isReaderDocumentOnScreen("\"${readerDocumentMarker(2)}\"", marker))
        assertFalse(isReaderDocumentOnScreen("\"\"", marker))
        assertFalse(isReaderDocumentOnScreen("null", marker))
    }
}
