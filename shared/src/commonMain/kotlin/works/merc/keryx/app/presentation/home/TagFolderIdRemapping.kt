package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.domain.TagFolderIdRemap

// Bringing the feed list's in-memory tag/folder ids up to date after a merge re-keyed rows
// (TagFolderIdRemaps) — see HomeViewModel.applyIdRemap.

/** This row with its tag or folder id brought up to date by [remap]; the same instance when nothing changes. */
internal fun FeedListRowSelection.remappedBy(remap: TagFolderIdRemap): FeedListRowSelection = when (this) {
    is FeedListRowSelection.Folder -> remap.folder(folderId).let { if (it == folderId) this else FeedListRowSelection.Folder(it) }
    is FeedListRowSelection.Tag -> remap.tag(tagId).let { if (it == tagId) this else FeedListRowSelection.Tag(it) }
    is FeedListRowSelection.FeedInTag -> remap.tag(tagId).let { if (it == tagId) this else copy(tagId = it) }
    FeedListRowSelection.All, FeedListRowSelection.Starred, is FeedListRowSelection.FeedInFolderGroup -> this
}
