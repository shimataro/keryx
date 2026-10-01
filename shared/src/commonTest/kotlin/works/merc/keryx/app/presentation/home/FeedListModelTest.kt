package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedListModelTest {

    @Test
    fun canonicalForMapsEveryFilterVariantToItsFolderGroupOrHeaderRow() {
        // A bare filter change (search jump, notification "show feed detail") has no specific
        // rendered row in mind, so a feed always resolves to its folder-group row, never a
        // tag-nested copy.
        assertEquals(FeedListRowSelection.All, FeedListRowSelection.canonicalFor(ArticleFilter.All))
        assertEquals(FeedListRowSelection.Starred, FeedListRowSelection.canonicalFor(ArticleFilter.Starred))
        assertEquals(
            FeedListRowSelection.FeedInFolderGroup("f1"),
            FeedListRowSelection.canonicalFor(ArticleFilter.Feed("f1")),
        )
        assertEquals(
            FeedListRowSelection.Folder("d1"),
            FeedListRowSelection.canonicalFor(ArticleFilter.Folder("d1")),
        )
        assertEquals(FeedListRowSelection.Tag("t1"), FeedListRowSelection.canonicalFor(ArticleFilter.Tag("t1")))
    }

    @Test
    fun feedListRowSelectionCarriesTheFilterEachRowSelects() {
        assertEquals(ArticleFilter.All, FeedListRowSelection.All.filter)
        assertEquals(ArticleFilter.Starred, FeedListRowSelection.Starred.filter)
        assertEquals(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInFolderGroup("f1").filter)
        assertEquals(ArticleFilter.Folder("d1"), FeedListRowSelection.Folder("d1").filter)
        assertEquals(ArticleFilter.Tag("t1"), FeedListRowSelection.Tag("t1").filter)
        // The two instances of one feed select the same filter but are different rows — the whole
        // point of the type.
        assertEquals(ArticleFilter.Feed("f1"), FeedListRowSelection.FeedInTag("f1", "t1").filter)
        assertNotEquals<FeedListRowSelection>(
            FeedListRowSelection.FeedInFolderGroup("f1"),
            FeedListRowSelection.FeedInTag("f1", "t1"),
        )
    }

    // --- buildOrderedFeedListRows ---

    @Test
    fun buildOrderedFeedListRowsPutsSidebarRowsFirstThenUnassignedFeedsThenTagsWhenNoFolders() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))

        val ordered = buildOrderedFeedListRows(tags, emptyList(), feeds, emptySet(), emptySet(), emptyMap())

        assertEquals(
            listOf(
                FeedListRowSelection.All,
                FeedListRowSelection.Starred,
                FeedListRowSelection.FeedInFolderGroup("f1"),
                FeedListRowSelection.FeedInFolderGroup("f2"),
                FeedListRowSelection.Tag("t1"),
                FeedListRowSelection.Tag("t2"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFeedListRowsPutsFolderGroupsBeforeUnassignedFeedsAndTags() {
        val tags = listOf(tag("t1"))
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2"))

        val ordered = buildOrderedFeedListRows(tags, folders, feeds, emptySet(), emptySet(), emptyMap())

        assertEquals(
            listOf(
                FeedListRowSelection.All,
                FeedListRowSelection.Starred,
                FeedListRowSelection.Folder("d1"),
                FeedListRowSelection.FeedInFolderGroup("f1"),
                FeedListRowSelection.FeedInFolderGroup("f2"),
                FeedListRowSelection.Tag("t1"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFeedListRowsSkipsFeedsUnderCollapsedFolders() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"))

        val ordered = buildOrderedFeedListRows(emptyList(), folders, feeds, setOf("d1"), emptySet(), emptyMap())

        assertEquals(
            listOf(
                FeedListRowSelection.All,
                FeedListRowSelection.Starred,
                FeedListRowSelection.Folder("d1"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFeedListRowsWithNoTagsOrFeedsHasOnlySidebarRows() {
        val ordered = buildOrderedFeedListRows(emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), emptyMap())

        assertEquals(
            listOf(FeedListRowSelection.All, FeedListRowSelection.Starred),
            ordered,
        )
    }

    @Test
    fun buildOrderedFeedListRowsPutsAnExpandedTagsFeedRowsRightAfterThatTag() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1", "t2"), "f2" to setOf("t1"))

        val ordered = buildOrderedFeedListRows(tags, emptyList(), feeds, emptySet(), setOf("t1"), feedTagMap)

        assertEquals(
            listOf(
                FeedListRowSelection.All,
                FeedListRowSelection.Starred,
                FeedListRowSelection.FeedInFolderGroup("f1"),
                FeedListRowSelection.FeedInFolderGroup("f2"),
                FeedListRowSelection.Tag("t1"),
                FeedListRowSelection.FeedInTag("f1", "t1"),
                FeedListRowSelection.FeedInTag("f2", "t1"),
                // t2 is collapsed, so f1's row under it is not navigable.
                FeedListRowSelection.Tag("t2"),
            ),
            ordered,
        )
    }

    @Test
    fun buildOrderedFeedListRowsOmitsTagNestedRowsWhileTheTagIsCollapsed() {
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1"))
        val feedTagMap = mapOf("f1" to setOf("t1"))

        val ordered = buildOrderedFeedListRows(tags, emptyList(), feeds, emptySet(), emptySet(), feedTagMap)

        assertEquals(
            listOf(
                FeedListRowSelection.All,
                FeedListRowSelection.Starred,
                FeedListRowSelection.FeedInFolderGroup("f1"),
                FeedListRowSelection.Tag("t1"),
            ),
            ordered,
        )
    }

    // --- nextFeedListRow ---

    @Test
    fun nextFeedListRowMovesForwardAndBackwardWithinBounds() {
        val ordered = buildOrderedFeedListRows(
            listOf(tag("t1")),
            emptyList(),
            listOf(feed("f1")),
            emptySet(),
            emptySet(),
            emptyMap(),
        )

        assertEquals(FeedListRowSelection.Starred, nextFeedListRow(FeedListRowSelection.All, ordered, 1))
        assertEquals(
            FeedListRowSelection.Tag("t1"),
            nextFeedListRow(FeedListRowSelection.FeedInFolderGroup("f1"), ordered, 1),
        )
        assertEquals(FeedListRowSelection.All, nextFeedListRow(FeedListRowSelection.Starred, ordered, -1))
    }

    @Test
    fun nextFeedListRowStepsFromAnExpandedTagIntoItsNestedFeedRowsAndBackOut() {
        val tags = listOf(tag("t1"), tag("t2"))
        val feeds = listOf(feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"))
        val ordered = buildOrderedFeedListRows(tags, emptyList(), feeds, emptySet(), setOf("t1"), feedTagMap)

        // Down from the tag row lands on its first nested feed, not on the next tag.
        assertEquals(
            FeedListRowSelection.FeedInTag("f1", "t1"),
            nextFeedListRow(FeedListRowSelection.Tag("t1"), ordered, 1),
        )
        assertEquals(
            FeedListRowSelection.FeedInTag("f2", "t1"),
            nextFeedListRow(FeedListRowSelection.FeedInTag("f1", "t1"), ordered, 1),
        )
        // Past the last nested row, navigation continues into the next tag.
        assertEquals(
            FeedListRowSelection.Tag("t2"),
            nextFeedListRow(FeedListRowSelection.FeedInTag("f2", "t1"), ordered, 1),
        )
        // And back up out of the nested rows onto the tag itself.
        assertEquals(
            FeedListRowSelection.Tag("t1"),
            nextFeedListRow(FeedListRowSelection.FeedInTag("f1", "t1"), ordered, -1),
        )
    }

    // --- reorderTargetWithinScope ---

    @Test
    fun reorderTargetWithinScopeMovesUpToJustBeforeThePrecedingSibling() {
        val ids = listOf("a", "b", "c")

        assertEquals(ReorderTarget("a"), reorderTargetWithinScope(ids, index = 1, delta = -1))
        assertEquals(ReorderTarget("b"), reorderTargetWithinScope(ids, index = 2, delta = -1))
    }

    @Test
    fun reorderTargetWithinScopeMovesDownToJustBeforeTheSiblingAfterTheNextOne() {
        val ids = listOf("a", "b", "c")

        // "a" moving down lands after "b", i.e. before "c".
        assertEquals(ReorderTarget("c"), reorderTargetWithinScope(ids, index = 0, delta = 1))
        // "b" moving down lands after the last one, i.e. appended (a null target — see reorderIds).
        assertEquals(ReorderTarget(null), reorderTargetWithinScope(ids, index = 1, delta = 1))
    }

    @Test
    fun reorderTargetWithinScopeReportsNoMoveAtEitherEndOfTheScope() {
        val ids = listOf("a", "b")

        assertNull(reorderTargetWithinScope(ids, index = 0, delta = -1))
        assertNull(reorderTargetWithinScope(ids, index = 1, delta = 1))
        assertNull(reorderTargetWithinScope(ids, index = 0, delta = -1))
        // A lone item has no sibling to swap with in either direction.
        assertNull(reorderTargetWithinScope(listOf("a"), index = 0, delta = 1))
        assertNull(reorderTargetWithinScope(listOf("a"), index = 0, delta = -1))
        // Out-of-range positions (a row whose data changed under it) are a no-op, not a crash.
        assertNull(reorderTargetWithinScope(ids, index = 5, delta = -1))
        assertNull(reorderTargetWithinScope(emptyList(), index = 0, delta = 1))
    }

    // --- articleListTitle ---

    @Test
    fun articleListTitleResolvesEachFilterVariantToItsDisplayName() {
        val feeds = listOf(feed("f1"))
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))

        assertEquals(
            "All",
            articleListTitle(ArticleFilter.All, feeds, folders, tags, "All", "Starred"),
        )
        assertEquals(
            "Starred",
            articleListTitle(ArticleFilter.Starred, feeds, folders, tags, "All", "Starred"),
        )
        assertEquals(
            "Feed f1",
            articleListTitle(ArticleFilter.Feed("f1"), feeds, folders, tags, "All", "Starred"),
        )
        assertEquals(
            "Folder d1",
            articleListTitle(ArticleFilter.Folder("d1"), feeds, folders, tags, "All", "Starred"),
        )
        assertEquals(
            "Tag t1",
            articleListTitle(ArticleFilter.Tag("t1"), feeds, folders, tags, "All", "Starred"),
        )
    }

    @Test
    fun articleListTitleFallsBackToAllLabelForAMissingFeedTagOrFolder() {
        assertEquals(
            "All",
            articleListTitle(ArticleFilter.Feed("gone"), emptyList(), emptyList(), emptyList(), "All", "Starred"),
        )
        assertEquals(
            "All",
            articleListTitle(ArticleFilter.Folder("gone"), emptyList(), emptyList(), emptyList(), "All", "Starred"),
        )
        assertEquals(
            "All",
            articleListTitle(ArticleFilter.Tag("gone"), emptyList(), emptyList(), emptyList(), "All", "Starred"),
        )
    }

    @Test
    fun nextFeedListRowTreatsAFeedsFolderRowAndItsTagNestedRowAsDifferentPositions() {
        val tags = listOf(tag("t1"))
        val feeds = listOf(feed("f1"))
        val feedTagMap = mapOf("f1" to setOf("t1"))
        val ordered = buildOrderedFeedListRows(tags, emptyList(), feeds, emptySet(), setOf("t1"), feedTagMap)

        // From the folder-group row, down lands on the tag; from the tag-nested row of the *same*
        // feed, down is the list end (null). A filter-keyed lookup couldn't tell these apart.
        assertEquals(
            FeedListRowSelection.Tag("t1"),
            nextFeedListRow(FeedListRowSelection.FeedInFolderGroup("f1"), ordered, 1),
        )
        assertNull(nextFeedListRow(FeedListRowSelection.FeedInTag("f1", "t1"), ordered, 1))
    }

    @Test
    fun nextFeedListRowAtTopBoundaryReturnsNullInsteadOfReselectingCurrent() {
        val ordered = buildOrderedFeedListRows(emptyList(), emptyList(), emptyList(), emptySet(), emptySet(), emptyMap())

        assertNull(nextFeedListRow(FeedListRowSelection.All, ordered, -1))
    }

    @Test
    fun nextFeedListRowAtBottomBoundaryReturnsNullInsteadOfReselectingCurrent() {
        val ordered = buildOrderedFeedListRows(
            emptyList(),
            emptyList(),
            listOf(feed("f1")),
            emptySet(),
            emptySet(),
            emptyMap(),
        )

        assertNull(nextFeedListRow(FeedListRowSelection.FeedInFolderGroup("f1"), ordered, 1))
    }

    @Test
    fun nextFeedListRowFallsBackToFirstEntryWhenCurrentIsNotInOrderedList() {
        val ordered = buildOrderedFeedListRows(
            emptyList(),
            emptyList(),
            listOf(feed("f1"), feed("f2")),
            emptySet(),
            emptySet(),
            emptyMap(),
        )

        // A row for a feed that's already been unsubscribed (stale/defensive case): treated as
        // if currently at index 0 (`All`), so moving forward by 1 lands on the next entry, `Starred`.
        assertEquals(
            FeedListRowSelection.Starred,
            nextFeedListRow(FeedListRowSelection.FeedInFolderGroup("gone"), ordered, 1),
        )
    }

    @Test
    fun hasUsableUrlRejectsNullEmptyAndBlank() {
        assertEquals(false, hasUsableUrl(null))
        assertEquals(false, hasUsableUrl(""))
        assertEquals(false, hasUsableUrl("   "))
        assertEquals(true, hasUsableUrl("https://example.com"))
    }

    @Test
    fun canOpenInBrowserAcceptsOnlyHttpAndHttps() {
        for (url in listOf(
            null, "", "   ", "file:///etc/passwd", "javascript:alert(1)", "keryx://oauth2/callback",
            "mailto:someone@example.com", "/relative/path", "example.com/no-scheme", "ftp://example.com",
        )) {
            assertEquals(false, canOpenInBrowser(url), "should reject $url")
        }
        for (url in listOf("https://x", "http://x", "HTTP://X", "HtTpS://example.com/a?b=c", "  https://padded.example  ")) {
            assertEquals(true, canOpenInBrowser(url), "should accept $url")
        }
    }

    @Test
    fun groupFeedsByFolderReturnsOnePairPerFolderInOrderPlusUnassignedLast() {
        val folders = listOf(folder("d1"), folder("d2"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2"))

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(3, groups.size)
        assertEquals("d1", groups[0].first?.id)
        assertEquals(listOf("f1"), groups[0].second.map { it.id })
        assertEquals("d2", groups[1].first?.id)
        assertEquals(emptyList(), groups[1].second)
        assertNull(groups[2].first)
        assertEquals(listOf("f2"), groups[2].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderKeepsZeroFeedFolderAsEmptyGroup() {
        val folders = listOf(folder("d1"))

        val groups = groupFeedsByFolder(emptyList(), folders)

        assertEquals(2, groups.size)
        assertEquals("d1", groups[0].first?.id)
        assertEquals(emptyList(), groups[0].second)
        assertNull(groups[1].first)
        assertEquals(emptyList(), groups[1].second)
    }

    @Test
    fun groupFeedsByFolderPutsFeedWithMissingOrDeletedFolderInUnassignedBucket() {
        // Only "d1" is a live folder; "d-gone" isn't in the list (either non-existent or soft-deleted).
        val folders = listOf(folder("d1"))
        val feeds = listOf(feed("f1", folderId = "d1"), feed("f2", folderId = "d-gone"), feed("f3", folderId = null))

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("f1"), groups[0].second.map { it.id })
        assertNull(groups[1].first)
        assertEquals(listOf("f2", "f3"), groups[1].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderPreservesInputFeedOrderEvenWhenNotAlphabetical() {
        // Feed titles/ids here are deliberately out of alphabetical order — grouping must
        // preserve whatever order the caller's list already has (a manually-arranged sort_order),
        // not silently re-sort by name.
        val folders = listOf(folder("d1"))
        val feeds = listOf(
            feed("zzz", folderId = "d1"),
            feed("mmm", folderId = "d1"),
            feed("aaa", folderId = "d1"),
        )

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("zzz", "mmm", "aaa"), groups[0].second.map { it.id })
    }

    @Test
    fun groupFeedsByFolderPreservesInputFeedOrderWithinEachGroup() {
        val folders = listOf(folder("d1"))
        val feeds = listOf(
            feed("f3", folderId = "d1"),
            feed("f1", folderId = "d1"),
            feed("f2", folderId = "d1"),
        )

        val groups = groupFeedsByFolder(feeds, folders)

        assertEquals(listOf("f3", "f1", "f2"), groups[0].second.map { it.id })
    }

    @Test
    fun feedsForTagIsEmptyWhenNoFeedCarriesTheTag() {
        val feeds = listOf(feed("f1"), feed("f2"))

        assertEquals(emptyList(), feedsForTag(feeds, emptyMap(), "t1"))
    }

    @Test
    fun feedsForTagPreservesInputFeedOrder() {
        val feeds = listOf(feed("f3"), feed("f1"), feed("f2"))
        val feedTagMap = mapOf("f1" to setOf("t1"), "f2" to setOf("t1"), "f3" to setOf("t1"))

        assertEquals(listOf("f3", "f1", "f2"), feedsForTag(feeds, feedTagMap, "t1").map { it.id })
    }

    @Test
    fun resolveFeedListSelectionTargetResolvesTheSelectedFeed() {
        val feeds = listOf(feed("f1"), feed("f2"))

        val target = resolveFeedListSelectionTarget(ArticleFilter.Feed("f2"), feeds, emptyList(), emptyList())

        assertEquals(FeedListSelectionTarget.Feed(feeds[1]), target)
    }

    @Test
    fun resolveFeedListSelectionTargetResolvesTheSelectedFolder() {
        val folders = listOf(folder("d1"), folder("d2"))

        val target = resolveFeedListSelectionTarget(ArticleFilter.Folder("d2"), emptyList(), folders, emptyList())

        assertEquals(FeedListSelectionTarget.Folder(folders[1]), target)
    }

    @Test
    fun resolveFeedListSelectionTargetResolvesTheSelectedTag() {
        val tags = listOf(tag("t1"), tag("t2"))

        val target = resolveFeedListSelectionTarget(ArticleFilter.Tag("t2"), emptyList(), emptyList(), tags)

        assertEquals(FeedListSelectionTarget.Tag(tags[1]), target)
    }

    @Test
    fun resolveFeedListSelectionTargetReturnsNullWhenTheSelectedItemNoLongerExists() {
        val feeds = listOf(feed("f1"))
        val folders = listOf(folder("d1"))
        val tags = listOf(tag("t1"))

        assertNull(resolveFeedListSelectionTarget(ArticleFilter.Feed("gone"), feeds, folders, tags))
        assertNull(resolveFeedListSelectionTarget(ArticleFilter.Folder("gone"), feeds, folders, tags))
        assertNull(resolveFeedListSelectionTarget(ArticleFilter.Tag("gone"), feeds, folders, tags))
    }

    @Test
    fun hasHideableReadIsFalseWithNoReadRowsAtAll() {
        val rows = listOf(hideableArticle("a1", read = false), hideableArticle("a2", read = false))
        assertFalse(hasHideableRead(rows, selectedId = null))
        assertFalse(hasHideableRead(rows, selectedId = "a1"))
    }

    @Test
    fun hasHideableReadIsFalseWhenOnlyTheSelectedArticleIsRead() {
        val rows = listOf(hideableArticle("a1", read = true), hideableArticle("a2", read = false))
        assertFalse(hasHideableRead(rows, selectedId = "a1"))
    }

    @Test
    fun hasHideableReadIsTrueWhenAnUnselectedRowIsRead() {
        val rows = listOf(hideableArticle("a1", read = true), hideableArticle("a2", read = false))
        assertTrue(hasHideableRead(rows, selectedId = "a2"))
        // No selection at all: any read row counts.
        assertTrue(hasHideableRead(rows, selectedId = null))
    }
}

private fun hideableArticle(id: String, read: Boolean): ArticleListRow = ArticleListRow(
    id = id,
    feed_id = "f1",
    title = "Article $id",
    url = "u$id",
    published_at = 0L,
    created_at = 0L,
    is_read = if (read) 1L else 0L,
    is_starred = 0L,
)

private fun tag(id: String): Tags = Tags(
    id = id,
    name = "Tag $id",
    color = null,
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
)

private fun folder(id: String): Folders = Folders(
    id = id,
    name = "Folder $id",
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
)

private fun feed(id: String, folderId: String? = null, sortOrder: Long = 0L): Feeds = Feeds(
    id = id,
    url = "https://example.com/$id",
    site_url = null,
    title = "Feed $id",
    description = null,
    favicon_url = null,
    etag = null,
    last_modified = null,
    error_count = 0L,
    last_error = null,
    custom_title = null,
    folder_id = folderId,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = sortOrder,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
