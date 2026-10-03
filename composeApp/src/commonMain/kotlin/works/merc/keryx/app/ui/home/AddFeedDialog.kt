package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.DiscoveredFeedType
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.addFeedAlreadySubscribed
import works.merc.keryx.app.presentation.home.AddFeedController
import works.merc.keryx.app.presentation.home.AddFeedPhase
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_add_feed_article_count
import works.merc.keryx.app.resources.home_add_feed_confirm
import works.merc.keryx.app.resources.home_add_feed_hint
import works.merc.keryx.app.resources.home_add_feed_links_found
import works.merc.keryx.app.resources.home_add_feed_loading_preview
import works.merc.keryx.app.resources.home_add_feed_loading_subscribe
import works.merc.keryx.app.resources.home_add_feed_partial
import works.merc.keryx.app.resources.home_add_feed_clear_all
import works.merc.keryx.app.resources.home_add_feed_select_all
import works.merc.keryx.app.resources.home_add_feed_select_links
import works.merc.keryx.app.resources.home_add_feed_selected_count
import works.merc.keryx.app.resources.home_add_feed_subscribe
import works.merc.keryx.app.resources.home_add_feed_type_atom
import works.merc.keryx.app.resources.home_add_feed_type_rss
import works.merc.keryx.app.resources.home_already_subscribed
import works.merc.keryx.app.ui.common.FlatTextButton
import works.merc.keryx.app.ui.common.KeryxAlertDialog
import works.merc.keryx.app.ui.common.FlatCheckbox
import works.merc.keryx.app.ui.common.KeryxTextField
import works.merc.keryx.app.ui.i18n.userMessage

/**
 * Displays a dialog for previewing a feed URL, selecting discovered feeds, and subscribing to feeds.
 *
 * @param vm The view model used to resolve previews and subscribe to feeds.
 * @param feeds The feeds used to determine whether the entered URL is already subscribed.
 * @param onDismiss Called when the dialog is dismissed.
 * @param onSubscribed Called after all requested feeds are subscribed successfully.
 * @param initialUrl The URL the input starts with when the dialog first opens.
 */
@Composable
internal fun AddFeedDialog(
    vm: HomeViewModel,
    feeds: List<Feeds>,
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit,
    initialUrl: String = "",
) {
    // The typed URL is mirrored into saved instance state so it survives an Android configuration
    // change (rotation, multi-window resize), which recreates the composition and with it the
    // controller. Only the URL is restored — a preview in flight or already shown is simply asked
    // for again on the next confirm.
    var savedUrl by rememberSaveable { mutableStateOf(initialUrl) }
    val controller = remember(vm) { AddFeedController(vm::resolvePreview, vm::subscribeFeeds, initialUrl = savedUrl) }
    val state by controller.state.collectAsState()
    LaunchedEffect(state.url) { savedUrl = state.url }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = { scope.launch { if (controller.submit()) onSubscribed() } }
    val alreadySubscribed = addFeedAlreadySubscribed(state.url, feeds)

    KeryxAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.home_add_feed),
        text = {
            AddFeedDialogContent(
                url = state.url,
                onUrlChange = controller::setUrl,
                alreadySubscribed = alreadySubscribed,
                phase = state.phase,
                preview = state.preview,
                selectedCandidates = state.selectedCandidates,
                onToggleCandidate = controller::toggleCandidate,
                onSelectAll = controller::selectAllCandidates,
                onClearAll = controller::clearCandidates,
                errorException = state.error,
                partialResult = state.partialResult,
                onSubmit = submit,
            )
        },
        confirmText = if (state.hasResult) {
            stringResource(Res.string.home_add_feed_subscribe)
        } else {
            stringResource(Res.string.home_add_feed_confirm)
        },
        onConfirm = submit,
        confirmEnabled = state.confirmEnabled,
        dismissText = stringResource(Res.string.common_cancel),
    )
}

/**
 * Renders the URL input, feed preview, candidate selection, and operation feedback for the add-feed dialog.
 *
 * @param url The current feed URL input.
 * @param onUrlChange Updates the feed URL input.
 * @param alreadySubscribed Whether the entered URL is already subscribed.
 * @param phase The current preview or subscription phase.
 * @param preview The resolved feed preview, if available.
 * @param selectedCandidates The URLs selected from a multiple-feed preview.
 * @param onToggleCandidate Updates the selection state of a candidate URL.
 * @param onSelectAll Selects all discovered feed candidates.
 * @param onClearAll Clears all selected feed candidates.
 * @param errorException The error to display, if an operation failed.
 * @param partialResult The number of successful and failed subscriptions, if the result was partial.
 * @param onSubmit Submits the current URL or selected feeds.
 * @param modifier The modifier applied to the content layout.
 */
@Composable
internal fun AddFeedDialogContent(
    url: String,
    onUrlChange: (String) -> Unit,
    alreadySubscribed: Boolean,
    phase: AddFeedPhase?,
    preview: AddFeedPreview?,
    selectedCandidates: Set<String>,
    onToggleCandidate: (url: String, checked: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    errorException: KeryxException?,
    partialResult: Pair<Int, Int>? = null,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    Column(modifier) {
        KeryxTextField(
            value = url,
            onValueChange = onUrlChange,
            singleLine = true,
            placeholder = stringResource(Res.string.home_add_feed_hint),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        if (alreadySubscribed) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.home_already_subscribed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        phase?.let {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when (it) {
                            AddFeedPhase.Previewing -> Res.string.home_add_feed_loading_preview
                            AddFeedPhase.Subscribing -> Res.string.home_add_feed_loading_subscribe
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        when (preview) {
            is AddFeedPreview.Single -> {
                Spacer(Modifier.height(12.dp))
                Text(preview.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(
                        Res.plurals.home_add_feed_article_count,
                        preview.articleCount,
                        preview.articleCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AddFeedPreview.Multiple -> CandidateSelection(
                candidates = preview.candidates,
                selectedCandidates = selectedCandidates,
                onToggleCandidate = onToggleCandidate,
                onSelectAll = onSelectAll,
                onClearAll = onClearAll,
            )
            else -> {}
        }

        errorException?.let {
            Spacer(Modifier.height(8.dp))
            Text(userMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        partialResult?.let { (success, failed) ->
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(Res.plurals.home_add_feed_partial, success, success, failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Displays discovered feed links with controls for selecting individual or all candidates.
 *
 * @param candidates The feed links available for selection.
 * @param selectedCandidates The URLs of the currently selected feed links.
 * @param onToggleCandidate Updates the selection state of a feed link.
 * @param onSelectAll Selects all available feed links.
 * @param onClearAll Clears the current feed link selection.
 */
@Composable
private fun CandidateSelection(
    candidates: List<DiscoveredFeedLink>,
    selectedCandidates: Set<String>,
    onToggleCandidate: (url: String, checked: Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    val selectedCount = candidates.count { it.url in selectedCandidates }
    val allSelected = candidates.isNotEmpty() && selectedCount == candidates.size

    Spacer(Modifier.height(12.dp))
    Text(stringResource(Res.string.home_add_feed_links_found), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(Res.string.home_add_feed_select_links),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlatTextButton(onClick = { if (allSelected) onClearAll() else onSelectAll() }) {
            Text(
                stringResource(
                    if (allSelected) Res.string.home_add_feed_clear_all else Res.string.home_add_feed_select_all,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(Res.string.home_add_feed_selected_count, selectedCount, candidates.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    // Bounded height: the enclosing KeryxAlertDialog text slot is already a verticalScroll, so an
    // unbounded LazyColumn would be measured with infinite height and crash. Capping it also keeps
    // the (scroll-external, always-visible) confirm/cancel row on screen regardless of list length.
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 264.dp)) {
        items(candidates, key = { it.url }) { link ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FlatCheckbox(
                    checked = link.url in selectedCandidates,
                    onCheckedChange = { checked -> onToggleCandidate(link.url, checked) },
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp, end = 12.dp),
                )
                Column {
                    Text(link.title ?: link.url, style = MaterialTheme.typography.bodyMedium)
                    val typeLabel = when (link.type) {
                        DiscoveredFeedType.Rss -> stringResource(Res.string.home_add_feed_type_rss)
                        DiscoveredFeedType.Atom -> stringResource(Res.string.home_add_feed_type_atom)
                        null -> null
                    }
                    typeLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
