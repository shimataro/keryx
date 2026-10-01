package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

class InitialFocusTest {

    @Test
    fun firstLaunchFocusesTheFeedList() {
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane(null, filterRestored = false, articleRestored = false))
    }

    @Test
    fun aLostFilterFocusesTheFeedListEvenWhenAnArticlePaneWasSaved() {
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("ArticleList", filterRestored = false, articleRestored = true))
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("ArticleDetail", filterRestored = false, articleRestored = true))
    }

    @Test
    fun aSavedFeedListPaneIsRestored() {
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("FeedList", filterRestored = true, articleRestored = true))
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("FeedList", filterRestored = true, articleRestored = false))
    }

    @Test
    fun aSavedArticlePaneIsRestoredWhenItsArticleWas() {
        assertEquals(InitialHomePane.ArticleList, resolveInitialHomePane("ArticleList", filterRestored = true, articleRestored = true))
        assertEquals(InitialHomePane.ArticleDetail, resolveInitialHomePane("ArticleDetail", filterRestored = true, articleRestored = true))
    }

    @Test
    fun aSavedArticlePaneFallsBackToTheFeedListWhenItsArticleWasLost() {
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("ArticleList", filterRestored = true, articleRestored = false))
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("ArticleDetail", filterRestored = true, articleRestored = false))
    }

    @Test
    fun anUnknownOrMissingPaneNameFocusesTheFeedList() {
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane("Search", filterRestored = true, articleRestored = true))
        assertEquals(InitialHomePane.FeedList, resolveInitialHomePane(null, filterRestored = true, articleRestored = true))
    }
}
