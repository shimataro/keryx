package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.SubscribeOutcome
import works.merc.keryx.app.domain.addFeedCanSubscribe

/** Which network step the add-feed flow is waiting on, if any. */
enum class AddFeedPhase { Previewing, Subscribing }

/**
 * Everything the add-feed dialog shows. [phase] is non-null while a preview or subscribe is in
 * flight; [preview] is the resolved result for [url]; [partialResult] is `(succeeded, failed)` when
 * only some of the selected feeds could be subscribed.
 */
data class AddFeedState(
    val url: String = "",
    val phase: AddFeedPhase? = null,
    val preview: AddFeedPreview? = null,
    val selectedCandidates: Set<String> = emptySet(),
    val error: KeryxException? = null,
    val partialResult: Pair<Int, Int>? = null,
) {
    /** Whether a preview has resolved — the confirm action then subscribes instead of previewing. */
    val hasResult: Boolean get() = preview != null

    /** Whether the confirm action is available right now. */
    val confirmEnabled: Boolean
        get() = phase == null && if (hasResult) addFeedCanSubscribe(preview, selectedCandidates) else url.isNotBlank()
}

/**
 * The add-feed dialog's state machine, independent of any UI: type a URL → preview it (a single
 * feed, or the feed links a web page advertises) → pick candidates → subscribe. One instance lives
 * as long as one dialog does.
 *
 * @param resolvePreview Resolves a typed URL (e.g. [HomeViewModel.resolvePreview]).
 * @param subscribeFeeds Subscribes to feed URLs (e.g. [HomeViewModel.subscribeFeeds]).
 */
class AddFeedController(
    private val resolvePreview: suspend (String) -> AddFeedPreview,
    private val subscribeFeeds: suspend (List<String>) -> SubscribeOutcome,
) {
    private val _state = MutableStateFlow(AddFeedState())
    val state: StateFlow<AddFeedState> = _state.asStateFlow()

    /**
     * Edits the URL, discarding any preview/selection/error that belonged to the previous one. A
     * step already in flight keeps running (and keeps [AddFeedState.phase] set), so typing during a
     * preview can't unlock a second, concurrent submit.
     */
    fun setUrl(url: String) = _state.update { AddFeedState(url = url, phase = it.phase) }

    fun toggleCandidate(candidateUrl: String, checked: Boolean) = _state.update {
        it.copy(selectedCandidates = if (checked) it.selectedCandidates + candidateUrl else it.selectedCandidates - candidateUrl)
    }

    fun selectAllCandidates() = _state.update { s ->
        val multiple = s.preview as? AddFeedPreview.Multiple ?: return@update s
        s.copy(selectedCandidates = multiple.candidates.map { it.url }.toSet())
    }

    fun clearCandidates() = _state.update { it.copy(selectedCandidates = emptySet()) }

    /**
     * The confirm action, which does double duty: previews when there is no result yet, subscribes
     * once there is. A no-op while a step is already in flight.
     *
     * Safe to call concurrently (e.g. from a Swift caller not confined to the main actor, or a
     * double-tap racing a recomposition): the in-flight check and the transition into the step are
     * one atomic [MutableStateFlow.compareAndSet], so at most one call ever starts a step. A call
     * whose snapshot went stale before it could claim the step is treated as "already in flight".
     *
     * @return `true` when every requested feed was subscribed — the dialog's cue to close.
     */
    suspend fun submit(): Boolean {
        val s = _state.value
        return when {
            s.phase != null -> false
            s.preview != null -> {
                if (!addFeedCanSubscribe(s.preview, s.selectedCandidates)) return false
                val started = s.copy(phase = AddFeedPhase.Subscribing, error = null, partialResult = null)
                _state.compareAndSet(s, started) && runSubscribe(started)
            }
            s.url.isNotBlank() -> {
                val started = s.copy(phase = AddFeedPhase.Previewing, error = null)
                if (_state.compareAndSet(s, started)) runPreview(started.url)
                false
            }
            else -> false
        }
    }

    /** Resolves [requestedUrl]; the caller has already moved [AddFeedState.phase] to Previewing. */
    private suspend fun runPreview(requestedUrl: String) {
        // Resolved before the update, never inside it: update's lambda re-runs when the state moved
        // under it (e.g. the URL was edited meanwhile), which would repeat the network request.
        val result = resolvePreview(requestedUrl)
        _state.update { s ->
            // The URL was edited while this preview was in flight: setUrl already discarded the old
            // preview, so the newer input wins and this stale result is dropped (only the phase ends).
            if (s.url != requestedUrl) return@update s.copy(phase = null)
            when (result) {
                is AddFeedPreview.Single -> s.copy(url = result.resolvedUrl, preview = result, selectedCandidates = emptySet())
                is AddFeedPreview.Multiple -> s.copy(preview = result, selectedCandidates = result.candidates.map { it.url }.toSet())
                is AddFeedPreview.Failed -> s.copy(preview = null, selectedCandidates = emptySet(), error = result.exception)
            }.copy(phase = null)
        }
    }

    /** Subscribes what [s] (the state the caller moved into Subscribing) had selected. */
    private suspend fun runSubscribe(s: AddFeedState): Boolean {
        val outcome = when (val p = s.preview) {
            is AddFeedPreview.Single -> subscribeFeeds(listOf(p.resolvedUrl))
            is AddFeedPreview.Multiple -> subscribeFeeds(s.selectedCandidates.toList())
            else -> null
        }
        _state.update { it.copy(phase = null) }
        if (outcome == null) return false
        return when {
            outcome.successCount > 0 && outcome.failCount == 0 -> true
            outcome.successCount > 0 -> {
                _state.update { it.copy(partialResult = outcome.successCount to outcome.failCount) }
                false
            }
            else -> {
                _state.update { it.copy(error = outcome.firstError) }
                false
            }
        }
    }
}
