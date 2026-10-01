package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags

/**
 * Whether [name] is already used by a folder other than [excludeId] — `null` (the default) excludes
 * nothing, for a create dialog with no existing row to exclude; a rename dialog passes the row's own
 * id so it doesn't flag itself. [folders] is expected to already exclude soft-deleted rows, as
 * [works.merc.keryx.app.domain.FolderRepository.watchAllFolders] does.
 */
fun isDuplicateFolderName(name: String, folders: List<Folders>, excludeId: String? = null): Boolean =
    folders.any { it.id != excludeId && it.name == name }

/**
 * Whether [name] is already used by a tag other than [excludeId] — see [isDuplicateFolderName] for
 * the parameter shapes. [tags] is expected to already exclude soft-deleted rows, as
 * [works.merc.keryx.app.domain.TagRepository.watchAllTags] does.
 */
fun isDuplicateTagName(name: String, tags: List<Tags>, excludeId: String? = null): Boolean =
    tags.any { it.id != excludeId && it.name == name }
