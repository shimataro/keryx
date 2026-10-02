package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.SettingsRepository

/**
 * The feed list's folder/tag expansion state: which folders are collapsed (folders default to
 * expanded) and which tags are expanded (tags default to collapsed).
 *
 * Seeded from local settings and persisted back on every change — expansion state is device-local
 * and never synced. Owned by [HomeViewModel], which keeps its own public facade over it.
 */
internal class FeedListExpansion(private val settingsRepository: SettingsRepository) {

    private val _collapsedFolderIds = MutableStateFlow(settingsRepository.getLocalSettings().collapsedFolderIds)

    /** The ids of the folders currently collapsed. */
    val collapsedFolderIds: StateFlow<Set<String>> = _collapsedFolderIds.asStateFlow()

    private val _expandedTagIds = MutableStateFlow(settingsRepository.getLocalSettings().expandedTagIds)

    /** The ids of the tags whose attached-feed list is currently expanded. */
    val expandedTagIds: StateFlow<Set<String>> = _expandedTagIds.asStateFlow()

    /**
     * Toggles whether a folder is collapsed and persists the updated state.
     *
     * @param folderId The identifier of the folder to toggle.
     */
    fun toggleFolder(folderId: String) {
        setCollapsedFolders(_collapsedFolderIds.value.let { if (folderId in it) it - folderId else it + folderId })
    }

    /**
     * Toggles whether a tag's attached-feed list is expanded and persists the updated state.
     *
     * @param tagId The identifier of the tag to toggle.
     * @return Whether the tag is expanded after the toggle.
     */
    fun toggleTag(tagId: String): Boolean {
        val expanded = tagId !in _expandedTagIds.value
        setExpandedTags(if (expanded) _expandedTagIds.value + tagId else _expandedTagIds.value - tagId)
        return expanded
    }

    /**
     * Expands whatever collapsed folder or tag hides the exact row [instance] (see
     * [containersToRevealFor]). Idempotent: an already-visible row changes nothing and writes nothing.
     *
     * @param instance The rendered feed-list row to reveal.
     * @param feeds The current feeds, used to resolve a feed's folder.
     */
    fun reveal(instance: FeedListRowSelection, feeds: List<Feeds>) {
        val reveal = containersToRevealFor(instance, feeds, _collapsedFolderIds.value, _expandedTagIds.value)
        reveal.folderToExpand?.let { setCollapsedFolders(_collapsedFolderIds.value - it) }
        reveal.tagToExpand?.let { setExpandedTags(_expandedTagIds.value + it) }
    }

    /** Drops a deleted folder from the collapsed-folder state. */
    fun forgetFolder(folderId: String) = setCollapsedFolders(_collapsedFolderIds.value - folderId)

    /** Drops a deleted tag from the expanded-tag state. */
    fun forgetTag(tagId: String) = setExpandedTags(_expandedTagIds.value - tagId)

    private fun setCollapsedFolders(ids: Set<String>) {
        _collapsedFolderIds.value = ids
        settingsRepository.mutateLocalSettings { it.copy(collapsedFolderIds = ids) }
    }

    private fun setExpandedTags(ids: Set<String>) {
        _expandedTagIds.value = ids
        settingsRepository.mutateLocalSettings { it.copy(expandedTagIds = ids) }
    }
}
