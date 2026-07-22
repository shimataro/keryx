package works.merc.keryx.app.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.ic_add_outlined
import works.merc.keryx.app.resources.ic_article_filled
import works.merc.keryx.app.resources.ic_check_filled
import works.merc.keryx.app.resources.ic_check_outlined
import works.merc.keryx.app.resources.ic_chevron_right_outlined
import works.merc.keryx.app.resources.ic_circle_outlined
import works.merc.keryx.app.resources.ic_close_filled
import works.merc.keryx.app.resources.ic_close_outlined
import works.merc.keryx.app.resources.ic_cloud_outlined
import works.merc.keryx.app.resources.ic_computer_outlined
import works.merc.keryx.app.resources.ic_content_copy_outlined
import works.merc.keryx.app.resources.ic_create_new_folder_outlined
import works.merc.keryx.app.resources.ic_delete_sweep_outlined
import works.merc.keryx.app.resources.ic_done_all_outlined
import works.merc.keryx.app.resources.ic_error_filled
import works.merc.keryx.app.resources.ic_error_outlined
import works.merc.keryx.app.resources.ic_expand_more_outlined
import works.merc.keryx.app.resources.ic_file_download_outlined
import works.merc.keryx.app.resources.ic_file_upload_outlined
import works.merc.keryx.app.resources.ic_folder_filled
import works.merc.keryx.app.resources.ic_info_outlined
import works.merc.keryx.app.resources.ic_link_off_outlined
import works.merc.keryx.app.resources.ic_new_label_outlined
import works.merc.keryx.app.resources.ic_notifications_outlined
import works.merc.keryx.app.resources.ic_public_filled
import works.merc.keryx.app.resources.ic_public_outlined
import works.merc.keryx.app.resources.ic_refresh_outlined
import works.merc.keryx.app.resources.ic_restart_alt_outlined
import works.merc.keryx.app.resources.ic_search_outlined
import works.merc.keryx.app.resources.ic_sort_outlined
import works.merc.keryx.app.resources.ic_star_border
import works.merc.keryx.app.resources.ic_star_filled
import works.merc.keryx.app.resources.ic_storage_outlined
import works.merc.keryx.app.resources.ic_tune_outlined
import works.merc.keryx.app.resources.ic_update_outlined
import works.merc.keryx.app.resources.ic_warning_outlined

/**
 * Central registry mapping semantic icon names to bundled vector [DrawableResource]s, replacing the
 * deprecated/frozen `material-icons-extended` library (CMP-9684). The Filled/Outlined choice per
 * icon is fixed here (chrome actions use Outlined, semantic state uses Filled — see the
 * `ui-guidelines` skill) so call sites just reference a name and any future swap is a one-file edit.
 *
 * Icons where both variants are used carry an explicit `Outlined`/`Filled` suffix; single-variant
 * icons keep a bare name. Assets are the classic Material Icons set (Apache-2.0).
 */
object KeryxIcons {
    // Chrome / actions (Outlined)
    val Add: DrawableResource = Res.drawable.ic_add_outlined
    val ChevronRight: DrawableResource = Res.drawable.ic_chevron_right_outlined
    val Cloud: DrawableResource = Res.drawable.ic_cloud_outlined
    val Computer: DrawableResource = Res.drawable.ic_computer_outlined
    val ContentCopy: DrawableResource = Res.drawable.ic_content_copy_outlined
    val Circle: DrawableResource = Res.drawable.ic_circle_outlined
    val CreateNewFolder: DrawableResource = Res.drawable.ic_create_new_folder_outlined
    val DeleteSweep: DrawableResource = Res.drawable.ic_delete_sweep_outlined
    val DoneAll: DrawableResource = Res.drawable.ic_done_all_outlined
    val ExpandMore: DrawableResource = Res.drawable.ic_expand_more_outlined
    val FileDownload: DrawableResource = Res.drawable.ic_file_download_outlined
    val FileUpload: DrawableResource = Res.drawable.ic_file_upload_outlined
    val Info: DrawableResource = Res.drawable.ic_info_outlined
    val LinkOff: DrawableResource = Res.drawable.ic_link_off_outlined
    val NewLabel: DrawableResource = Res.drawable.ic_new_label_outlined
    val Notifications: DrawableResource = Res.drawable.ic_notifications_outlined
    val Refresh: DrawableResource = Res.drawable.ic_refresh_outlined
    val RestartAlt: DrawableResource = Res.drawable.ic_restart_alt_outlined
    val Search: DrawableResource = Res.drawable.ic_search_outlined
    val Sort: DrawableResource = Res.drawable.ic_sort_outlined
    val Storage: DrawableResource = Res.drawable.ic_storage_outlined
    val Tune: DrawableResource = Res.drawable.ic_tune_outlined
    val Update: DrawableResource = Res.drawable.ic_update_outlined
    val Warning: DrawableResource = Res.drawable.ic_warning_outlined

    // Semantic state (Filled)
    val Article: DrawableResource = Res.drawable.ic_article_filled
    val Folder: DrawableResource = Res.drawable.ic_folder_filled
    val Star: DrawableResource = Res.drawable.ic_star_filled
    val StarBorder: DrawableResource = Res.drawable.ic_star_border

    // Icons used in both variants
    val CheckOutlined: DrawableResource = Res.drawable.ic_check_outlined
    val CheckFilled: DrawableResource = Res.drawable.ic_check_filled
    val CloseOutlined: DrawableResource = Res.drawable.ic_close_outlined
    val CloseFilled: DrawableResource = Res.drawable.ic_close_filled
    val ErrorOutlined: DrawableResource = Res.drawable.ic_error_outlined
    val ErrorFilled: DrawableResource = Res.drawable.ic_error_filled
    val PublicOutlined: DrawableResource = Res.drawable.ic_public_outlined
    val PublicFilled: DrawableResource = Res.drawable.ic_public_filled
}

/**
 * Thin drop-in replacement for `androidx.compose.material3.Icon(imageVector = …)` that takes a
 * bundled [DrawableResource] instead. Mirrors [Icon]'s parameter order and defaults (tint falls back
 * to [LocalContentColor], matching the vector-icon overload) so migrating a call site is just a name
 * swap.
 */
@Composable
fun KeryxIcon(
    icon: DrawableResource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = Icon(painterResource(icon), contentDescription, modifier, tint)
