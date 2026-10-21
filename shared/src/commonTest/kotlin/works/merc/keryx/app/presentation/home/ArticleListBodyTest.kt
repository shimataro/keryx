package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers [articleListBody], which decides the article list's whole-pane states for both UIs. */
class ArticleListBodyTest {

    @Test
    fun isPendingUntilBothListsHaveLoaded() {
        assertEquals(ArticleListBody.Pending, articleListBody(hasFeeds = null, articlesLoaded = false))
        assertEquals(ArticleListBody.Pending, articleListBody(hasFeeds = true, articlesLoaded = false))
        assertEquals(ArticleListBody.Pending, articleListBody(hasFeeds = false, articlesLoaded = false))
        assertEquals(ArticleListBody.Pending, articleListBody(hasFeeds = null, articlesLoaded = true))
    }

    @Test
    fun noFeedsOnceLoadedWithoutAnyFeed() {
        assertEquals(ArticleListBody.NoFeeds, articleListBody(hasFeeds = false, articlesLoaded = true))
    }

    @Test
    fun contentOnceLoadedWithFeedsWhetherOrNotTheListIsEmpty() {
        // "No articles" is decided from the list a UI renders, not here.
        assertEquals(ArticleListBody.Content, articleListBody(hasFeeds = true, articlesLoaded = true))
    }

    @Test
    fun aUiShowingNoRowsWhileTheViewModelHoldsSomeIsStillCatchingUp() {
        assertEquals(true, articleListCatchingUp(displayedRows = 0, snapshotHasRows = true))
    }

    @Test
    fun aUiShowingRowsOrBeingGenuinelyEmptyIsNotCatchingUp() {
        assertEquals(false, articleListCatchingUp(displayedRows = 3, snapshotHasRows = true))
        assertEquals(false, articleListCatchingUp(displayedRows = 0, snapshotHasRows = false))
        assertEquals(false, articleListCatchingUp(displayedRows = 3, snapshotHasRows = false))
    }
}
