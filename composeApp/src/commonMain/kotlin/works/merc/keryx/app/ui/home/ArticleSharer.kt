package works.merc.keryx.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.ShareSheet
import works.merc.keryx.app.platform.platformSupportsShare
import works.merc.keryx.app.presentation.home.canShareArticleUrl
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_share_chooser_title

/**
 * The one handler behind every route to "Share" an article — the reader's toolbar button and the
 * article row's context menu (`.claude/skills/ui-guidelines`'s "Actions with more than one route").
 * Both routes read the same enablement ([canShare], i.e. the shared [canShareArticleUrl]) and call
 * [share], which applies that same rule as its guard before handing the URL to the OS share sheet
 * ([ShareSheet]). The sheet itself is the feedback, so there is none of the app's own.
 *
 * Exists only where the platform has a share sheet: [rememberArticleSharer] returns `null` otherwise
 * (desktop), and a `null` sharer is what keeps every share route off screen there.
 *
 * @param chooserTitle The share sheet's heading, where the OS still shows one.
 * @param launch The platform share — [ShareSheet.shareText]; a parameter so tests can observe it.
 */
@Stable
class ArticleSharer(
    private val chooserTitle: String,
    private val launch: (text: String, subject: String?, chooserTitle: String) -> Unit = ShareSheet::shareText,
) {
    /** Whether an article with [url] can be shared — every route's enabled state. */
    fun canShare(url: String?): Boolean = canShareArticleUrl(url)

    /** Shares [url] with [title] as its subject — nothing when [canShare] is `false`. */
    fun share(url: String?, title: String?) {
        if (url == null || !canShare(url)) return
        launch(url, title?.takeIf { it.isNotBlank() }, chooserTitle)
    }
}

/**
 * Remembers this screen's [ArticleSharer], or `null` where the platform offers no share sheet
 * ([platformSupportsShare]) — in which case no route shows a share action at all.
 */
@Composable
fun rememberArticleSharer(supported: Boolean = platformSupportsShare): ArticleSharer? {
    if (!supported) return null
    val chooserTitle = stringResource(Res.string.article_share_chooser_title)
    return remember(chooserTitle) { ArticleSharer(chooserTitle) }
}
