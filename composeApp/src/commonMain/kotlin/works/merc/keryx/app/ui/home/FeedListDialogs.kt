package works.merc.keryx.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.presentation.home.isDuplicateFolderName
import works.merc.keryx.app.presentation.home.isDuplicateTagName
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_delete
import works.merc.keryx.app.resources.home_add_folder
import works.merc.keryx.app.resources.home_add_tag
import works.merc.keryx.app.resources.home_delete_folder_confirm
import works.merc.keryx.app.resources.home_delete_folder_menu
import works.merc.keryx.app.resources.home_delete_tag_confirm
import works.merc.keryx.app.resources.home_delete_tag_menu
import works.merc.keryx.app.resources.home_folder_name_duplicate
import works.merc.keryx.app.resources.home_new_folder_hint
import works.merc.keryx.app.resources.home_new_tag_hint
import works.merc.keryx.app.resources.home_tag_name_duplicate
import works.merc.keryx.app.resources.home_unsubscribe_body
import works.merc.keryx.app.resources.home_unsubscribe_title
import works.merc.keryx.app.ui.common.KeryxConfirmDialog

/**
 * The feed-list sidebar's modal dialogs: add tag, add folder, the delete/unsubscribe confirmations.
 * Split out of `FeedListPane` — each `on*Change` callback mirrors the
 * `var x by remember { mutableStateOf(...) }` state it replaces there, so behavior is unchanged.
 *
 * **Renaming is not here**: a feed/folder/tag name is edited in its own row (see `InlineRename.kt`).
 * Creating still uses a dialog — there is no row to edit in place yet, and a new tag picks its name
 * and color at once.
 *
 * @param tags The tags used for duplicate-name validation.
 * @param folders The folders used for duplicate-name validation.
 * @param tagOrdinals Each tag's ordinal among the tags sharing its name, by tag id
 *   ([works.merc.keryx.app.presentation.home.tagOrdinals]) — numbers the tag a delete
 *   confirmation names, so it says which of several same-named tags is meant.
 * @param folderOrdinals Each folder's ordinal among the folders sharing its name, by folder id.
 */
@Composable
internal fun FeedListDialogs(
    vm: HomeViewModel,
    tags: List<Tags>,
    folders: List<Folders>,
    tagOrdinals: Map<String, Int> = emptyMap(),
    folderOrdinals: Map<String, Int> = emptyMap(),
    showAddTag: Boolean,
    onShowAddTagChange: (Boolean) -> Unit,
    confirmingDeleteTag: Tags?,
    onConfirmingDeleteTagChange: (Tags?) -> Unit,
    showAddFolder: Boolean,
    onShowAddFolderChange: (Boolean) -> Unit,
    confirmingDeleteFolder: Folders?,
    onConfirmingDeleteFolderChange: (Folders?) -> Unit,
    confirmingUnsubscribeFeed: Feeds?,
    onConfirmingUnsubscribeFeedChange: (Feeds?) -> Unit,
    creatingFolderForFeedId: String?,
    onCreatingFolderForFeedIdChange: (String?) -> Unit,
    creatingTagForFeedId: String?,
    onCreatingTagForFeedIdChange: (String?) -> Unit,
) {
    if (showAddTag) {
        AddTagPrompt(
            tags = tags,
            onConfirm = { name, color -> vm.createTag(name, color); onShowAddTagChange(false) },
            onDismiss = { onShowAddTagChange(false) },
        )
    }
    creatingTagForFeedId?.let { feedId ->
        AddTagPrompt(
            tags = tags,
            onConfirm = { name, color ->
                vm.createTag(name, color, attachToFeedId = feedId)
                onCreatingTagForFeedIdChange(null)
            },
            onDismiss = { onCreatingTagForFeedIdChange(null) },
        )
    }
    confirmingDeleteTag?.let { tag ->
        KeryxConfirmDialog(
            title = stringResource(Res.string.home_delete_tag_menu),
            body = stringResource(Res.string.home_delete_tag_confirm, nameWithOrdinal(tag.name, tagOrdinals[tag.id])),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteTag(tag.id); onConfirmingDeleteTagChange(null) },
            onDismiss = { onConfirmingDeleteTagChange(null) },
            destructive = true,
        )
    }
    if (showAddFolder) {
        AddFolderPrompt(
            folders = folders,
            onConfirm = { vm.createFolder(it); onShowAddFolderChange(false) },
            onDismiss = { onShowAddFolderChange(false) },
        )
    }
    creatingFolderForFeedId?.let { feedId ->
        AddFolderPrompt(
            folders = folders,
            onConfirm = { name ->
                vm.createFolder(name, moveFeedId = feedId)
                onCreatingFolderForFeedIdChange(null)
            },
            onDismiss = { onCreatingFolderForFeedIdChange(null) },
        )
    }
    confirmingDeleteFolder?.let { folder ->
        KeryxConfirmDialog(
            title = stringResource(Res.string.home_delete_folder_menu),
            body = stringResource(Res.string.home_delete_folder_confirm, nameWithOrdinal(folder.name, folderOrdinals[folder.id])),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteFolder(folder.id); onConfirmingDeleteFolderChange(null) },
            onDismiss = { onConfirmingDeleteFolderChange(null) },
            destructive = true,
        )
    }
    confirmingUnsubscribeFeed?.let { feed ->
        val displayName = feed.displayTitle()
        KeryxConfirmDialog(
            title = stringResource(Res.string.home_unsubscribe_title, displayName),
            body = stringResource(Res.string.home_unsubscribe_body),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.unsubscribeFeed(feed.id); onConfirmingUnsubscribeFeedChange(null) },
            onDismiss = { onConfirmingUnsubscribeFeedChange(null) },
            destructive = true,
        )
    }
}

/** The "add tag" prompt — a name plus a color — shared by the toolbar's add and a feed row's "new tag". */
@Composable
private fun AddTagPrompt(
    tags: List<Tags>,
    onConfirm: (name: String, color: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val duplicateError = stringResource(Res.string.home_tag_name_duplicate)
    var color by remember { mutableStateOf<String?>(null) }
    TextPromptDialog(
        title = stringResource(Res.string.home_add_tag),
        hint = stringResource(Res.string.home_new_tag_hint),
        blockingError = { name -> if (isDuplicateTagName(name, tags)) duplicateError else null },
        extraContent = { TagColorPicker(selected = color, onSelect = { color = it }) },
        onConfirm = { onConfirm(it, color) },
        onDismiss = onDismiss,
    )
}

/** The "add folder" prompt, shared by the toolbar's add and a feed row's "new folder". */
@Composable
private fun AddFolderPrompt(
    folders: List<Folders>,
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val duplicateError = stringResource(Res.string.home_folder_name_duplicate)
    TextPromptDialog(
        title = stringResource(Res.string.home_add_folder),
        hint = stringResource(Res.string.home_new_folder_hint),
        blockingError = { name -> if (isDuplicateFolderName(name, folders)) duplicateError else null },
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
