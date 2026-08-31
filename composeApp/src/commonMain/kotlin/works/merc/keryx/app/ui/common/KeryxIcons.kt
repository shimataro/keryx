package works.merc.keryx.app.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Central registry mapping semantic icon names to bundled vector [DrawableResource]s. The
 * Filled/Outlined choice per icon is fixed here (chrome actions use Outlined, semantic state uses
 * Filled — see the `ui-guidelines` skill) so call sites just reference a name and any future swap
 * is a one-file edit.
 *
 * Icons where both variants are used carry an explicit `Outlined`/`Filled` suffix; single-variant
 * icons keep a bare name.
 *
 * `expect`/`actual` per platform since the two targets intentionally bundle different icon sets:
 * the desktop `actual` uses Tabler Icons (MIT) — chosen for a thin-stroke, rounded-terminal look
 * closer to macOS's own iconography than Material Design's, without redistributing Apple's own SF
 * Symbols (whose license restricts them to Apple-platform apps, and this app also ships
 * Windows/Linux builds) — while the Android `actual` uses Material Symbols (Apache-2.0) to match
 * Android's own native visual language. **When remapping either `actual` to a different icon set
 * later, don't just match each icon by semantic name** — grep for
 * `graphicsLayer`/`rotate`/`scaleX`/`scaleY` modifiers applied around each `KeryxIcon(...)` call
 * site first; see `ui-guidelines`' "Native-feel restyle" section for the concrete incident this
 * warning comes from (Tabler's `arrows-sort` silently breaking `ArticleListPane`'s sort-direction
 * flip). A two-state icon therefore gets a dedicated asset per state rather than one asset
 * transformed at the call site: [SortAscending]/[SortDescending] and [ArrowBack] (as opposed to a
 * flipped [ChevronRight]) both exist for that reason.
 */
expect object KeryxIcons {
    // Chrome / actions (Outlined)
    val Add: DrawableResource
    val ArrowBack: DrawableResource
    val ChevronRight: DrawableResource
    val Cloud: DrawableResource
    val Computer: DrawableResource
    val ContentCopy: DrawableResource
    val Circle: DrawableResource
    val CreateNewFolder: DrawableResource
    val Delete: DrawableResource
    val DeleteSweep: DrawableResource
    val DoneAll: DrawableResource
    val DragHandle: DrawableResource
    val ExpandMore: DrawableResource
    val FileDownload: DrawableResource
    val FileUpload: DrawableResource
    val Info: DrawableResource
    val Link: DrawableResource
    val LinkOff: DrawableResource
    val NewLabel: DrawableResource
    val Notifications: DrawableResource
    val Refresh: DrawableResource
    val RestartAlt: DrawableResource
    val Search: DrawableResource
    val SortAscending: DrawableResource
    val SortDescending: DrawableResource
    val Storage: DrawableResource
    val Tune: DrawableResource
    val Update: DrawableResource
    val Warning: DrawableResource

    // Semantic state (Filled)
    val Article: DrawableResource
    val Folder: DrawableResource
    val Star: DrawableResource
    val StarBorder: DrawableResource

    // Icons used in both variants
    val CheckOutlined: DrawableResource
    val CheckFilled: DrawableResource
    val CloseOutlined: DrawableResource
    val CloseFilled: DrawableResource
    val ErrorOutlined: DrawableResource
    val ErrorFilled: DrawableResource
    val PublicOutlined: DrawableResource
    val PublicFilled: DrawableResource
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
