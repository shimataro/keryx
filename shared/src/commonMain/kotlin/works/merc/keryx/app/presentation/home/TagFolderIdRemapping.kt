package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.domain.TagFolderIdRemap
import works.merc.keryx.app.domain.TagFolderIdRemaps

/**
 * Brings every tag/folder id [HomeViewModel] holds up to date after a merge re-keyed rows onto
 * other ids ([TagFolderIdRemaps]), so nothing the user had selected or open is lost to a re-key
 * (external-spec §9, "Least surprise"): the selected filter and row move to the id the tag or
 * folder has now instead of falling back to All Feeds, the feed list's expansion keeps the same
 * folders collapsed and tags expanded, a pull in progress keeps its indicator, and a delete still
 * waiting for its item to leave the feed list waits for the row it became. The local settings are
 * rewritten by `SettingsRepository` from the same value.
 *
 * Every method runs on the main thread, like every other change to the state it holds, so it needs
 * no lock. All of it is idempotent — the map is folded and no live id is a key — so the value at
 * hand when collection starts, which usually changes nothing, is applied harmlessly, and so is a
 * collector applying a map a [catchUp] has already applied.
 *
 * The filter is replaced directly rather than through `selectFilter`: the selection stays where it
 * is, so its article, pins and cursor are kept, and its generation ([FilterSelection]) with them,
 * so the article list neither scrolls back to the top nor loses its new-articles pill, and a pull
 * in progress on it keeps its indicator ([HomeRefreshController.remapPulls]).
 */
internal class TagFolderIdRemapping(
    private val idRemaps: TagFolderIdRemaps,
    private val filterSelection: MutableStateFlow<FilterSelection>,
    private val selectedRowInstance: MutableStateFlow<FeedListRowSelection>,
    private val expansion: FeedListExpansion,
    private val refreshController: HomeRefreshController,
    private val deletedTagFallback: DeletedItemFallback<*>,
    private val deletedFolderFallback: DeletedItemFallback<*>,
) {
    /** Applies each map [TagFolderIdRemaps] publishes, as it arrives, for as long as [scope] lives. */
    fun collectIn(scope: CoroutineScope) {
        idRemaps.current.onEach(::apply).launchIn(scope)
    }

    /**
     * Applies the current re-key map ([TagFolderIdRemaps.current]) to the state held now. The
     * collector ([collectIn]) applies each published map, but on the main dispatcher, so it can lag
     * the map's current value: an action that reads that value synchronously would otherwise compare
     * or toggle an id already moved against state still holding the old one — every action that reads
     * the map catches up first ([catchUpAndGet]).
     */
    fun catchUp() {
        apply(idRemaps.current.value)
    }

    /** [catchUp], then the map it applied — the one an action may resolve the ids it was given by. */
    fun catchUpAndGet(): TagFolderIdRemap {
        val remap = idRemaps.current.value
        apply(remap)
        return remap
    }

    private fun apply(remap: TagFolderIdRemap) {
        if (remap.isEmpty) return
        // The same selection under the tag's or folder's new id: the generation stays, so neither UI
        // treats it as a switch (the list keeps its scroll position and its new-articles pill).
        filterSelection.update { it.remappedBy(remap) }
        refreshController.remapPulls(remap)
        val instance = selectedRowInstance.value.remappedBy(remap)
        if (instance != selectedRowInstance.value) selectedRowInstance.value = instance
        expansion.remap(remap)
        deletedTagFallback.remapPending(remap::tag)
        deletedFolderFallback.remapPending(remap::folder)
    }
}

// The id-space rewrites of the state above — see [TagFolderIdRemapping.apply].

/** This row with its tag or folder id brought up to date by [remap]; the same instance when nothing changes. */
internal fun FeedListRowSelection.remappedBy(remap: TagFolderIdRemap): FeedListRowSelection = when (this) {
    is FeedListRowSelection.Folder -> remap.folder(folderId).let { if (it == folderId) this else FeedListRowSelection.Folder(it) }
    is FeedListRowSelection.Tag -> remap.tag(tagId).let { if (it == tagId) this else FeedListRowSelection.Tag(it) }
    is FeedListRowSelection.FeedInTag -> remap.tag(tagId).let { if (it == tagId) this else copy(tagId = it) }
    FeedListRowSelection.All, FeedListRowSelection.Starred, is FeedListRowSelection.FeedInFolderGroup -> this
}

/** This selection with its filter's tag or folder id brought up to date by [remap], keeping its generation. */
internal fun FilterSelection.remappedBy(remap: TagFolderIdRemap): FilterSelection =
    remap.filter(filter).let { if (it == filter) this else copy(filter = it) }
