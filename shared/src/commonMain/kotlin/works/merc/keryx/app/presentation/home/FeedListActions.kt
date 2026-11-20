package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.flow.MutableStateFlow
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.TagRepository

/**
 * The feed-list structure actions of [HomeViewModel] — creating, renaming and deleting tags and
 * folders, attaching tags, moving feeds and reordering folders, and toggling a tag's expansion —
 * kept out of the ViewModel itself, which only delegates to this. A plain class the ViewModel
 * creates and owns, not a Koin binding.
 *
 * @param expansion The ViewModel's feed-list expansion state, forgotten for a deleted tag/folder.
 * @param selectedRowInstance The ViewModel's selected feed-list row, moved off a tag-nested row
 *   that stops rendering.
 * @param currentFilter The article list's current selection.
 * @param selectFilter Selects a filter on the ViewModel (with its canonical row), used to fall back
 *   to [ArticleFilter.All] when the selected tag/folder is deleted.
 * @param launchWrite Queues a DB write to run off the caller's thread and returns at once — in
 *   production the ViewModel's `feedListWrites` ([SerialWriteQueue] on its `dbWriteDispatcher`), so a
 *   write never blocks the UI thread on the SQLite write lock (a sync merge or search-index write can
 *   hold it for up to `busy_timeout`). Writes run one at a time, each to completion — including
 *   while it is suspended, e.g. a move waiting on the feed placement lock — before the next starts,
 *   so they land in the order they were made (the ViewModel's unsubscribe and rename share the same
 *   queue). The second argument, when non-null, runs on the ViewModel's main thread once the write
 *   has landed successfully — never after a failure, which the queue reports itself. A delete hands
 *   its selection fallback (and the expansion state it forgets) to [fallBackOnceHidden] there and
 *   changes nothing before, so a delete that fails leaves the filter, the open article and the
 *   expansion exactly as they were. Creating an item and placing a feed in it is one write
 *   ([TagRepository.createTagAndAttach], [FolderRepository.createFolderAndMoveFeed]), so a failure
 *   leaves neither half behind; no action shows anything ahead of its write.
 * @param fallBackOnceHidden Runs the fallback it is given once the feed list stops showing the
 *   deleted target — in production the ViewModel's [DeletedItemFallback]s. A delete's write lands
 *   before the list flow delivers it, so the feed list still shows the deleted item, and the user can
 *   select it again, until then; the gap closes only when the list stops showing it, not when the
 *   write lands. Called from a delete's `afterWrite`, so only for a delete this class made and whose
 *   write succeeded.
 */
internal class FeedListActions(
    private val feedRepository: FeedRepository,
    private val tagRepository: TagRepository,
    private val folderRepository: FolderRepository,
    private val expansion: FeedListExpansion,
    private val selectedRowInstance: MutableStateFlow<FeedListRowSelection>,
    private val currentFilter: () -> ArticleFilter,
    private val selectFilter: (ArticleFilter) -> Unit,
    private val launchWrite: (write: suspend () -> Unit, afterWrite: (() -> Unit)?) -> Unit,
    private val fallBackOnceHidden: (target: DeletedTarget, fallback: () -> Unit) -> Unit,
) {
    /** See `HomeViewModel.toggleTagExpanded`. */
    fun toggleTagExpanded(tagId: String) {
        val expanded = expansion.toggleTag(tagId)
        // A collapsed tag no longer renders its nested feed rows, so a selection on one of them
        // falls back to that feed's canonical row.
        if (!expanded) fallBackFromTagRow(tagId)
    }

    // --- Tag actions ---

    /** See `HomeViewModel.createTag`. Blank names are ignored. */
    fun createTag(name: String, color: String?, attachToFeedId: String? = null) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite({
            if (attachToFeedId != null) {
                tagRepository.createTagAndAttach(trimmed, color, attachToFeedId)
            } else {
                tagRepository.createTag(trimmed, color)
            }
        }, null)
    }

    /** See `HomeViewModel.updateTag`. Blank names are ignored. */
    fun updateTag(id: String, name: String, color: String?) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite({ tagRepository.updateTag(id, trimmed, color) }, null)
    }

    /**
     * See `HomeViewModel.deleteTag`. The selection falls back (and the tag's expansion state is
     * forgotten) only once the write has landed and the feed list has stopped showing the tag — so a
     * tag (or a feed row nested under it) selected again in between falls back too, and a delete that
     * fails changes nothing.
     */
    fun deleteTag(id: String) {
        launchWrite({ tagRepository.deleteTag(id) }) {
            fallBackOnceHidden(DeletedTarget.Tag(id)) { fallBackFromDeletedTag(id) }
        }
    }

    private fun fallBackFromDeletedTag(id: String) {
        if (currentFilter() == ArticleFilter.Tag(id)) selectFilter(ArticleFilter.All)
        // A deleted tag no longer renders its nested feed rows, so a selection on one of them
        // falls back to that feed's canonical row (a no-op if the branch above already reset it).
        fallBackFromTagRow(id)
        expansion.forgetTag(id)
    }

    /** Moves a selection on a feed row nested under [tagId] to that feed's canonical row. */
    private fun fallBackFromTagRow(tagId: String) {
        val instance = selectedRowInstance.value
        if (instance is FeedListRowSelection.FeedInTag && instance.tagId == tagId) {
            selectedRowInstance.value = FeedListRowSelection.FeedInFolderGroup(instance.feedId)
        }
    }

    fun setFeedTag(feedId: String, tagId: String, attached: Boolean) {
        launchWrite({ tagRepository.setFeedTag(feedId, tagId, attached) }, null)
    }

    // --- Folder actions ---

    /** See `HomeViewModel.createFolder`. Blank names are ignored. */
    fun createFolder(name: String, moveFeedId: String? = null) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite({
            if (moveFeedId != null) {
                folderRepository.createFolderAndMoveFeed(trimmed, moveFeedId)
            } else {
                folderRepository.createFolder(trimmed)
            }
        }, null)
    }

    /** See `HomeViewModel.updateFolder`. Blank names are ignored. */
    fun updateFolder(id: String, name: String) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite({ folderRepository.updateFolder(id, trimmed) }, null)
    }

    /**
     * See `HomeViewModel.deleteFolder`. The selection falls back (and the folder's collapsed state is
     * forgotten) only once the write has landed and the feed list has stopped showing the folder — so
     * a folder selected again in between falls back too, and a delete that fails changes nothing.
     */
    fun deleteFolder(id: String) {
        launchWrite({ folderRepository.deleteFolder(id) }) {
            fallBackOnceHidden(DeletedTarget.Folder(id)) { fallBackFromDeletedFolder(id) }
        }
    }

    private fun fallBackFromDeletedFolder(id: String) {
        if (currentFilter() == ArticleFilter.Folder(id)) selectFilter(ArticleFilter.All)
        expansion.forgetFolder(id)
    }

    fun moveFeed(feedId: String, folderId: String?, targetFeedId: String?) {
        launchWrite({ feedRepository.moveFeed(feedId, folderId, targetFeedId) }, null)
    }

    fun reorderFolders(draggedFolderId: String, targetFolderId: String?) {
        launchWrite({ folderRepository.reorderFolders(draggedFolderId, targetFolderId) }, null)
    }
}
