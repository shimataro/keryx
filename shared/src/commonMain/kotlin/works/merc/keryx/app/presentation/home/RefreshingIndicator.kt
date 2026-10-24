package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds

/**
 * The folders whose row shows the refreshing indicator: those that are collapsed and hold a feed in
 * [refreshingFeedIds]. An expanded folder shows nothing, since its feeds' own rows already do.
 */
fun collapsedFoldersRefreshing(
    feeds: List<Feeds>,
    refreshingFeedIds: Set<String>,
    collapsedFolderIds: Set<String>,
): Set<String> {
    if (refreshingFeedIds.isEmpty() || collapsedFolderIds.isEmpty()) return emptySet()
    return feeds.asSequence()
        .filter { it.id in refreshingFeedIds }
        .mapNotNull { it.folder_id }
        .filter { it in collapsedFolderIds }
        .toSet()
}
