package works.merc.keryx.app.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_url_copied
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.platformShowsOwnCopyConfirmation
import works.merc.keryx.app.presentation.home.articleUrlCopyPlan

/**
 * The one handler behind every route to "copy article URL" — the reader's toolbar button, the
 * keyboard shortcut, the menu bar and the article row's context menu (`.claude/skills/ui-guidelines`'s
 * "Actions with more than one route"). Owning the whole action here, feedback included, is what
 * keeps the routes identical: the feedback used to live in the reader, so a copy made while the
 * reader wasn't composed (a phone-width article list) or for a row other than the one it shows
 * (an Android long-press doesn't select first) wrote the clipboard silently.
 *
 * [copy] carries out the shared [articleUrlCopyPlan] (the same decision the SwiftUI app uses): it
 * writes the clipboard when the plan says so, then:
 * - bumps [pulse] — which the reader watches to flash its copy button's inline ✓ — when the plan
 *   flashes it (the copied article is the one the reader shows, [displayedArticleId]);
 * - shows the "URL copied" snackbar when [showsSnackbar] and a [snackbarHostState] exist. That is
 *   Android below API 33 only: desktop has no in-app snackbar convention (no host — see
 *   `LocalSnackbarHostState`), and from API 33 the OS shows its own clipboard confirmation
 *   ([platformShowsOwnCopyConfirmation]). One snackbar per copy: a copy made while the previous
 *   one's snackbar is still up replaces it rather than queueing behind it.
 */
@Stable
class ArticleUrlCopier(
    private val scope: CoroutineScope,
    private val clipboard: Clipboard,
    private val snackbarHostState: SnackbarHostState?,
    private val copiedMessage: String,
    private val showsSnackbar: Boolean = !platformShowsOwnCopyConfirmation,
    private val displayedArticleId: () -> String?,
) {
    /** Bumped once per copy of the displayed article's URL; see the class KDoc. */
    var pulse by mutableIntStateOf(0)
        private set

    private var copyJob: Job? = null

    /** Copies [url] (article [articleId]'s) as [articleUrlCopyPlan] decides — nothing when it isn't usable. */
    fun copy(url: String?, articleId: String) {
        val plan = articleUrlCopyPlan(url, articleId, displayedArticleId())
        if (!plan.writeClipboard || url == null) return
        val previous = copyJob
        copyJob = scope.launch {
            // Dismisses the previous copy's snackbar (showSnackbar clears it when cancelled).
            previous?.cancel()
            clipboard.setClipEntry(ClipboardEntries.ofText(url))
            if (plan.flashCopied) pulse++
            if (showsSnackbar) snackbarHostState?.showSnackbar(copiedMessage)
        }
    }
}

/** Remembers an [ArticleUrlCopier] bound to this composition's scope and clipboard. */
@Composable
fun rememberArticleUrlCopier(
    snackbarHostState: SnackbarHostState?,
    displayedArticleId: () -> String?,
): ArticleUrlCopier {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copiedMessage = stringResource(Res.string.article_url_copied)
    val currentDisplayedArticleId by rememberUpdatedState(displayedArticleId)
    return remember(scope, clipboard, snackbarHostState, copiedMessage) {
        ArticleUrlCopier(
            scope = scope,
            clipboard = clipboard,
            snackbarHostState = snackbarHostState,
            copiedMessage = copiedMessage,
            displayedArticleId = { currentDisplayedArticleId() },
        )
    }
}
