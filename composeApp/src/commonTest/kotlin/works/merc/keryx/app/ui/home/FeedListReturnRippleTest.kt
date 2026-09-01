package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for the "flash the row you just backed out of" feature on the feed list side
 * — see `ArticleReturnRippleTest` for the article-list mirror this is modeled on, and
 * `HomePaneLayoutTest`'s `shouldFlashReturnedFeedListRow` cases for when this is triggered at all.
 */
class FeedListReturnRippleTest {

    @Test
    fun feedListRipplePulseForReturnsThePulseForTheMatchingInstance() {
        val instance = FeedListRowSelection.Folder("f1")
        assertEquals(
            7,
            feedListRipplePulseFor(instance = instance, selectedInstance = instance, returnRipplePulse = 7),
        )
    }

    @Test
    fun feedListRipplePulseForReturnsZeroForAnyOtherInstance() {
        assertEquals(
            0,
            feedListRipplePulseFor(
                instance = FeedListRowSelection.Folder("f2"),
                selectedInstance = FeedListRowSelection.Folder("f1"),
                returnRipplePulse = 7,
            ),
        )
    }

    @Test
    fun feedListRipplePulseForReturnsZeroWhenThePulseItselfIsZero() {
        val instance = FeedListRowSelection.All
        assertEquals(
            0,
            feedListRipplePulseFor(instance = instance, selectedInstance = instance, returnRipplePulse = 0),
        )
    }

    @Test
    fun feedListRipplePulseForOnlyFlashesTheSelectedCopyOfADoublyRenderedFeed() {
        // The same feed renders once under its folder group and again under an expanded tag it
        // carries — only the instance the user actually navigated through should flash, matching
        // how `toneFor` paints only one of them RowSelectionTone.PRIMARY.
        val folderCopy = FeedListRowSelection.FeedInFolderGroup(feedId = "feed1")
        val tagCopy = FeedListRowSelection.FeedInTag(feedId = "feed1", tagId = "tag1")

        assertEquals(
            5,
            feedListRipplePulseFor(instance = tagCopy, selectedInstance = tagCopy, returnRipplePulse = 5),
        )
        assertEquals(
            0,
            feedListRipplePulseFor(instance = folderCopy, selectedInstance = tagCopy, returnRipplePulse = 5),
        )
    }
}
