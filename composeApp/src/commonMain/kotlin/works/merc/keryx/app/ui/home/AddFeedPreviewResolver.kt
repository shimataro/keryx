package works.merc.keryx.app.ui.home

import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.remote.UrlResolver
import works.merc.keryx.app.domain.FeedRepository

/** Outcome of [AddFeedPreviewResolver.resolvePreview] for the add-feed dialog. */
sealed interface AddFeedPreview {
    /** A single feed resolved directly. [title] falls back to [resolvedUrl] when the feed is untitled. */
    data class Single(val resolvedUrl: String, val title: String, val articleCount: Int) : AddFeedPreview

    /** The URL pointed at an HTML page advertising multiple feeds; the user picks which to subscribe. */
    data class Multiple(val candidates: List<DiscoveredFeedLink>) : AddFeedPreview

    /** Preview failed for a non-discovery reason. */
    data class Failed(val exception: KeryxException) : AddFeedPreview
}

/** Tally returned by [AddFeedPreviewResolver.subscribeFeeds]. */
data class SubscribeOutcome(val successCount: Int, val failCount: Int, val firstError: KeryxException?)

/**
     * Determines whether the add-feed dialog can enable the subscribe action.
     *
     * @param preview The current feed preview state.
     * @param selectedCandidates The feed URLs selected from a multiple-feed preview.
     * @return `true` if subscription is available for the preview, `false` otherwise.
     */
fun addFeedCanSubscribe(preview: AddFeedPreview?, selectedCandidates: Set<String>): Boolean =
    when (preview) {
        is AddFeedPreview.Single -> true
        is AddFeedPreview.Multiple -> selectedCandidates.isNotEmpty()
        else -> false
    }

/** Preview/subscribe orchestration for the add-feed dialog, split out of [HomeViewModel] to keep its surface smaller. */
class AddFeedPreviewResolver(private val feedRepository: FeedRepository) {
    /**
     * Previews [rawUrl] and maps the outcome for the add-feed dialog. Handles scheme resolution
     * (prepending `https://`, then retrying with `http://` when the user typed no scheme and the
     * https attempt failed for a non-discovery reason) and turns a [FeedDiscoveryException] into a
     * list of candidate feed links. The returned [AddFeedPreview.Single.resolvedUrl] is the actual
     * URL that resolved, so the dialog can both display it and subscribe with it.
     */
    suspend fun resolvePreview(rawUrl: String): AddFeedPreview {
        val trimmed = rawUrl.trim()
        val hadScheme = UrlResolver.hasScheme(trimmed)
        var attemptUrl = UrlResolver.withDefaultScheme(trimmed)
        var result = feedRepository.previewFeed(attemptUrl)
        if (!hadScheme && result is Result.Err && result.exception !is FeedDiscoveryException) {
            val httpUrl = "http://$trimmed"
            val httpResult = feedRepository.previewFeed(httpUrl)
            result = httpResult
            if (httpResult is Result.Ok) attemptUrl = httpUrl
        }
        return when (val r = result) {
            is Result.Ok -> AddFeedPreview.Single(
                resolvedUrl = attemptUrl,
                title = r.value.title ?: attemptUrl,
                articleCount = r.value.articles.size,
            )
            is Result.Err -> when (val ex = r.exception) {
                is FeedDiscoveryException -> AddFeedPreview.Multiple(ex.candidates)
                else -> AddFeedPreview.Failed(ex)
            }
        }
    }

    /** Subscribes to every URL in [urls], returning the success/failure tally and the first error. */
    suspend fun subscribeFeeds(urls: List<String>): SubscribeOutcome {
        val results = urls.map { feedRepository.subscribeFeed(it) }
        val successCount = results.count { it is Result.Ok }
        return SubscribeOutcome(
            successCount = successCount,
            failCount = results.size - successCount,
            firstError = results.filterIsInstance<Result.Err>().firstOrNull()?.exception,
        )
    }
}
