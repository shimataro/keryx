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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.DiscoveredFeedType
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.remote.UrlResolver
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

internal enum class AddFeedPhase { Previewing, Subscribing }

@Composable
internal fun AddFeedDialog(
    vm: HomeViewModel,
    feeds: List<Feeds>,
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<AddFeedPhase?>(null) }
    var preview by remember { mutableStateOf<AddFeedPreview?>(null) }
    var selectedCandidates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var errorException by remember { mutableStateOf<KeryxException?>(null) }
    var partialResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun runPreview() {
        phase = AddFeedPhase.Previewing
        errorException = null
        when (val result = vm.resolvePreview(url)) {
            is AddFeedPreview.Single -> {
                if (result.resolvedUrl != url) url = result.resolvedUrl
                preview = result
                selectedCandidates = emptySet()
            }
            is AddFeedPreview.Multiple -> {
                preview = result
                selectedCandidates = result.candidates.map { it.url }.toSet()
            }
            is AddFeedPreview.Failed -> {
                preview = null
                selectedCandidates = emptySet()
                errorException = result.exception
            }
        }
        phase = null
    }

    suspend fun runSubscribe() {
        phase = AddFeedPhase.Subscribing
        errorException = null
        partialResult = null
        val outcome = when (val p = preview) {
            is AddFeedPreview.Single -> vm.subscribeFeeds(listOf(p.resolvedUrl))
            is AddFeedPreview.Multiple -> vm.subscribeFeeds(selectedCandidates.toList())
            else -> null
        }
        phase = null
        if (outcome != null) {
            when {
                outcome.successCount > 0 && outcome.failCount == 0 -> onSubscribed()
                outcome.successCount > 0 -> partialResult = outcome.successCount to outcome.failCount
                else -> errorException = outcome.firstError
            }
        }
    }

    // Enter/confirm does double duty: preview when there's no result yet, subscribe once there is.
    suspend fun submit() {
        when {
            phase != null -> return
            preview != null -> if (addFeedCanSubscribe(preview, selectedCandidates)) runSubscribe()
            url.isNotBlank() -> runPreview()
        }
    }

    val hasResult = preview != null
    val confirmEnabled = phase == null &&
        if (hasResult) addFeedCanSubscribe(preview, selectedCandidates) else url.isNotBlank()
    val alreadySubscribed = url.isNotBlank() && feeds.any { it.url == UrlResolver.withDefaultScheme(url) }

    KeryxAlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.home_add_feed),
        text = {
            AddFeedDialogContent(
                url = url,
                onUrlChange = {
                    url = it
                    preview = null
                    selectedCandidates = emptySet()
                    errorException = null
                    partialResult = null
                },
                alreadySubscribed = alreadySubscribed,
                phase = phase,
                preview = preview,
                selectedCandidates = selectedCandidates,
                onToggleCandidate = { candidateUrl, checked ->
                    selectedCandidates =
                        if (checked) selectedCandidates + candidateUrl else selectedCandidates - candidateUrl
                },
                onSelectAll = {
                    (preview as? AddFeedPreview.Multiple)?.let {
                        selectedCandidates = it.candidates.map { link -> link.url }.toSet()
                    }
                },
                onClearAll = { selectedCandidates = emptySet() },
                errorException = errorException,
                partialResult = partialResult,
                onSubmit = { scope.launch { submit() } },
            )
        },
        confirmText = if (hasResult) {
            stringResource(Res.string.home_add_feed_subscribe)
        } else {
            stringResource(Res.string.home_add_feed_confirm)
        },
        onConfirm = { scope.launch { submit() } },
        confirmEnabled = confirmEnabled,
        dismissText = stringResource(Res.string.common_cancel),
    )
}

/**
 * Stateless body of [AddFeedDialog] (the `text` slot). Split out so it can be rendered directly in a
 * Compose UI test without the surrounding [KeryxAlertDialog]/OS-window wrapper. Renders the URL
 * field plus, depending on [preview], either the single-feed summary or the multi-candidate picker.
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
                    stringResource(Res.string.home_add_feed_article_count, preview.articleCount),
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
                stringResource(Res.string.home_add_feed_partial, success, failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

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
