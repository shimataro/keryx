package works.merc.keryx.app.ui.common

import org.jetbrains.compose.resources.DrawableResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.ic_add_material
import works.merc.keryx.app.resources.ic_arrow_back_material
import works.merc.keryx.app.resources.ic_article_material
import works.merc.keryx.app.resources.ic_check_filled_material
import works.merc.keryx.app.resources.ic_check_outlined_material
import works.merc.keryx.app.resources.ic_chevron_right_material
import works.merc.keryx.app.resources.ic_circle_material
import works.merc.keryx.app.resources.ic_close_filled_material
import works.merc.keryx.app.resources.ic_close_outlined_material
import works.merc.keryx.app.resources.ic_cloud_material
import works.merc.keryx.app.resources.ic_computer_material
import works.merc.keryx.app.resources.ic_content_copy_material
import works.merc.keryx.app.resources.ic_create_new_folder_material
import works.merc.keryx.app.resources.ic_delete_sweep_material
import works.merc.keryx.app.resources.ic_drag_handle_material
import works.merc.keryx.app.resources.ic_done_all_material
import works.merc.keryx.app.resources.ic_error_filled_material
import works.merc.keryx.app.resources.ic_error_outlined_material
import works.merc.keryx.app.resources.ic_expand_more_material
import works.merc.keryx.app.resources.ic_file_download_material
import works.merc.keryx.app.resources.ic_file_upload_material
import works.merc.keryx.app.resources.ic_folder_material
import works.merc.keryx.app.resources.ic_info_material
import works.merc.keryx.app.resources.ic_link_off_material
import works.merc.keryx.app.resources.ic_new_label_material
import works.merc.keryx.app.resources.ic_notifications_material
import works.merc.keryx.app.resources.ic_public_filled_material
import works.merc.keryx.app.resources.ic_public_outlined_material
import works.merc.keryx.app.resources.ic_refresh_material
import works.merc.keryx.app.resources.ic_restart_alt_material
import works.merc.keryx.app.resources.ic_search_material
import works.merc.keryx.app.resources.ic_sort_ascending_material
import works.merc.keryx.app.resources.ic_sort_descending_material
import works.merc.keryx.app.resources.ic_star_border_material
import works.merc.keryx.app.resources.ic_star_material
import works.merc.keryx.app.resources.ic_storage_material
import works.merc.keryx.app.resources.ic_tune_material
import works.merc.keryx.app.resources.ic_update_material
import works.merc.keryx.app.resources.ic_warning_material

/**
 * Android `actual`: Material Symbols Outlined (Apache-2.0, from
 * [google/material-design-icons](https://github.com/google/material-design-icons)) — matches
 * Android's own native visual language. See [KeryxIcons]'s own KDoc for why this diverges from the
 * desktop `actual`'s Tabler set.
 *
 * `CheckFilled`/`CloseFilled` use Material Symbols' `wght700` variant rather than its `fill1`
 * variant: `check`/`close` are pure strokes with no enclosed area, so the `fill` axis (which only
 * affects an icon's interior) leaves them visually identical to the unfilled glyph — `wght`
 * (stroke weight) is what actually reproduces the thin/thick distinction the desktop Tabler pair
 * uses `ic_check_outlined`/`ic_check_filled` for.
 */
actual object KeryxIcons {
    // Chrome / actions (Outlined)
    actual val Add: DrawableResource = Res.drawable.ic_add_material
    actual val ArrowBack: DrawableResource = Res.drawable.ic_arrow_back_material
    actual val ChevronRight: DrawableResource = Res.drawable.ic_chevron_right_material
    actual val Cloud: DrawableResource = Res.drawable.ic_cloud_material
    actual val Computer: DrawableResource = Res.drawable.ic_computer_material
    actual val ContentCopy: DrawableResource = Res.drawable.ic_content_copy_material
    actual val Circle: DrawableResource = Res.drawable.ic_circle_material
    actual val CreateNewFolder: DrawableResource = Res.drawable.ic_create_new_folder_material
    actual val DeleteSweep: DrawableResource = Res.drawable.ic_delete_sweep_material
    actual val DoneAll: DrawableResource = Res.drawable.ic_done_all_material
    actual val DragHandle: DrawableResource = Res.drawable.ic_drag_handle_material
    actual val ExpandMore: DrawableResource = Res.drawable.ic_expand_more_material
    actual val FileDownload: DrawableResource = Res.drawable.ic_file_download_material
    actual val FileUpload: DrawableResource = Res.drawable.ic_file_upload_material
    actual val Info: DrawableResource = Res.drawable.ic_info_material
    actual val LinkOff: DrawableResource = Res.drawable.ic_link_off_material
    actual val NewLabel: DrawableResource = Res.drawable.ic_new_label_material
    actual val Notifications: DrawableResource = Res.drawable.ic_notifications_material
    actual val Refresh: DrawableResource = Res.drawable.ic_refresh_material
    actual val RestartAlt: DrawableResource = Res.drawable.ic_restart_alt_material
    actual val Search: DrawableResource = Res.drawable.ic_search_material
    // Material Symbols ships no directional sort glyph, so these are local composites of the
    // stock `sort` (bars only) plus `arrow_downward`/`arrow_upward`.
    actual val SortAscending: DrawableResource = Res.drawable.ic_sort_ascending_material
    actual val SortDescending: DrawableResource = Res.drawable.ic_sort_descending_material
    actual val Storage: DrawableResource = Res.drawable.ic_storage_material
    actual val Tune: DrawableResource = Res.drawable.ic_tune_material
    actual val Update: DrawableResource = Res.drawable.ic_update_material
    actual val Warning: DrawableResource = Res.drawable.ic_warning_material

    // Semantic state (Filled)
    actual val Article: DrawableResource = Res.drawable.ic_article_material
    actual val Folder: DrawableResource = Res.drawable.ic_folder_material
    actual val Star: DrawableResource = Res.drawable.ic_star_material
    actual val StarBorder: DrawableResource = Res.drawable.ic_star_border_material

    // Icons used in both variants
    actual val CheckOutlined: DrawableResource = Res.drawable.ic_check_outlined_material
    actual val CheckFilled: DrawableResource = Res.drawable.ic_check_filled_material
    actual val CloseOutlined: DrawableResource = Res.drawable.ic_close_outlined_material
    actual val CloseFilled: DrawableResource = Res.drawable.ic_close_filled_material
    actual val ErrorOutlined: DrawableResource = Res.drawable.ic_error_outlined_material
    actual val ErrorFilled: DrawableResource = Res.drawable.ic_error_filled_material
    actual val PublicOutlined: DrawableResource = Res.drawable.ic_public_outlined_material
    actual val PublicFilled: DrawableResource = Res.drawable.ic_public_filled_material
}
