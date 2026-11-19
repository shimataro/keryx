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
 * @param launchWrite Runs a DB write off the caller's thread and returns at once — in production the
 *   ViewModel's serial `dbWriteDispatcher`, so a write never blocks the UI thread on the SQLite write
 *   lock (a sync merge or search-index write can hold it for up to `busy_timeout`) and consecutive
 *   writes still land in the order they were made. The UI-side state (selection fallback, expansion)
 *   is updated by the action itself, synchronously, before or regardless of the write.
 */
internal class FeedListActions(
    private val feedRepository: FeedRepository,
    private val tagRepository: TagRepository,
    private val folderRepository: FolderRepository,
    private val expansion: FeedListExpansion,
    private val selectedRowInstance: MutableStateFlow<FeedListRowSelection>,
    private val currentFilter: () -> ArticleFilter,
    private val selectFilter: (ArticleFilter) -> Unit,
    private val launchWrite: (suspend () -> Unit) -> Unit,
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
        launchWrite {
            val tagId = tagRepository.createTag(trimmed, color)
            if (attachToFeedId != null) tagRepository.setFeedTag(attachToFeedId, tagId, attached = true)
        }
    }

    /** See `HomeViewModel.updateTag`. Blank names are ignored. */
    fun updateTag(id: String, name: String, color: String?) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite { tagRepository.updateTag(id, trimmed, color) }
    }

    /** See `HomeViewModel.deleteTag`. */
    fun deleteTag(id: String) {
        launchWrite { tagRepository.deleteTag(id) }
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
        launchWrite { tagRepository.setFeedTag(feedId, tagId, attached) }
    }

    // --- Folder actions ---

    /** See `HomeViewModel.createFolder`. Blank names are ignored. */
    fun createFolder(name: String, moveFeedId: String? = null) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite {
            val folderId = folderRepository.createFolder(trimmed)
            if (moveFeedId != null) feedRepository.moveFeed(moveFeedId, folderId)
        }
    }

    /** See `HomeViewModel.updateFolder`. Blank names are ignored. */
    fun updateFolder(id: String, name: String) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        launchWrite { folderRepository.updateFolder(id, trimmed) }
    }

    /** See `HomeViewModel.deleteFolder`. */
    fun deleteFolder(id: String) {
        launchWrite { folderRepository.deleteFolder(id) }
        if (currentFilter() == ArticleFilter.Folder(id)) selectFilter(ArticleFilter.All)
        expansion.forgetFolder(id)
    }

    fun moveFeed(feedId: String, folderId: String?, targetFeedId: String?) {
        launchWrite { feedRepository.moveFeed(feedId, folderId, targetFeedId) }
    }

    fun reorderFolders(draggedFolderId: String, targetFolderId: String?) {
        launchWrite { folderRepository.reorderFolders(draggedFolderId, targetFolderId) }
    }
}
