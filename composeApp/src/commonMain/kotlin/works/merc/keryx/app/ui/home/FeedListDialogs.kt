package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
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
import works.merc.keryx.app.ui.common.KeryxAlertDialog

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
 */
@Composable
internal fun FeedListDialogs(
    vm: HomeViewModel,
    tags: List<Tags>,
    folders: List<Folders>,
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
) {
    if (showAddTag) {
        val duplicateError = stringResource(Res.string.home_tag_name_duplicate)
        var color by remember { mutableStateOf<String?>(null) }
        TextPromptDialog(
            title = stringResource(Res.string.home_add_tag),
            hint = stringResource(Res.string.home_new_tag_hint),
            initial = "",
            blockingError = { name -> if (tags.any { it.name == name }) duplicateError else null },
            extraContent = { TagColorPicker(selected = color, onSelect = { color = it }) },
            onConfirm = { vm.createTag(it, color); onShowAddTagChange(false) },
            onDismiss = { onShowAddTagChange(false) },
        )
    }
    confirmingDeleteTag?.let { tag ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { onConfirmingDeleteTagChange(null) },
            title = stringResource(Res.string.home_delete_tag_menu),
            text = { Text(stringResource(Res.string.home_delete_tag_confirm, tag.name)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteTag(tag.id); onConfirmingDeleteTagChange(null) },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    if (showAddFolder) {
        val duplicateError = stringResource(Res.string.home_folder_name_duplicate)
        TextPromptDialog(
            title = stringResource(Res.string.home_add_folder),
            hint = stringResource(Res.string.home_new_folder_hint),
            initial = "",
            blockingError = { name -> if (folders.any { it.name == name }) duplicateError else null },
            onConfirm = { vm.createFolder(it); onShowAddFolderChange(false) },
            onDismiss = { onShowAddFolderChange(false) },
        )
    }
    confirmingDeleteFolder?.let { folder ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { onConfirmingDeleteFolderChange(null) },
            title = stringResource(Res.string.home_delete_folder_menu),
            text = { Text(stringResource(Res.string.home_delete_folder_confirm, folder.name)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteFolder(folder.id); onConfirmingDeleteFolderChange(null) },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    confirmingUnsubscribeFeed?.let { feed ->
        val displayName = feed.displayTitle()
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { onConfirmingUnsubscribeFeedChange(null) },
            title = stringResource(Res.string.home_unsubscribe_title, displayName),
            text = { Text(stringResource(Res.string.home_unsubscribe_body)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.unsubscribeFeed(feed.id); onConfirmingUnsubscribeFeedChange(null) },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
}
