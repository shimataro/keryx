package works.merc.keryx.app.ui.home

import io.github.kdroidfilter.webview.web.LoadingState
import kotlin.test.Test
import kotlin.test.assertFalse
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
    fun neverLoadsWhileLoading() {
        assertFalse(shouldLoadArticleHtml(LoadingState.Loading(0.5f), loadedHtml = null, html = "<html>a</html>"))
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
}
