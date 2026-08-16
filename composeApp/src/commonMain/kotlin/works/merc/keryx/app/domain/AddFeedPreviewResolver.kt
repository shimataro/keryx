package works.merc.keryx.app.domain

import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedDiscoveryException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.remote.UrlResolver

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

/**
 * Determines whether the entered URL (after scheme normalization) already appears in [feeds].
 *
 * @param url The feed URL entered by the user.
 * @param feeds The current subscriptions to check against.
 * @return `true` if [url] matches an existing subscription, `false` otherwise.
 */
fun addFeedAlreadySubscribed(url: String, feeds: List<Feeds>): Boolean =
    url.isNotBlank() && feeds.any { it.url == UrlResolver.withDefaultScheme(url) }

/**
 * Preview/subscribe orchestration for the add-feed dialog, split out of `HomeViewModel` to keep
 * its surface smaller.
 */
class AddFeedPreviewResolver(
    private val feedRepository: FeedRepository,
    private val tagRepository: TagRepository,
) {
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

    /**
     * Subscribes to every URL in [urls], returning the success/failure tally and the first error.
     *
     * @param folderId The folder brand-new feeds should be filed into, or `null` for no folder.
     * @param afterFeedId The feed the first brand-new subscription should be inserted directly
     *   after, or `null` to append it at the end of its group. Each subsequent URL in [urls] then
     *   chains off the feed just subscribed before it, so a multi-URL batch lands in input order
     *   right after [afterFeedId] rather than being reversed or piling up at the group's end.
     * @param beforeFeedId The feed the first brand-new subscription should be inserted directly
     *   *before*, or `null`. Used when a folder — rather than a specific feed — is selected, so the
     *   new feed lands at the start of that folder; falls back to appending at the end of the group
     *   when the named feed isn't in it. Subsequent URLs chain off [afterFeedId] as above, so a
     *   multi-URL batch still lands in input order rather than reversed.
     * @param tagId The tag to attach to brand-new subscriptions, or `null` to attach none. Unlike
     *   the position anchors above — which only apply to the first URL and then chain — this
     *   applies to *every* successfully subscribed feed in the batch, since a tag is a
     *   classification rather than a position.
     */
    suspend fun subscribeFeeds(
        urls: List<String>,
        folderId: String? = null,
        afterFeedId: String? = null,
        beforeFeedId: String? = null,
        tagId: String? = null,
    ): SubscribeOutcome {
        var insertAfter = afterFeedId
        var insertBefore = beforeFeedId
        val results = urls.map { url ->
            val result = feedRepository.subscribeFeed(url, folderId, insertAfter, insertBefore)
            if (result is Result.Ok) {
                insertAfter = result.value.id
                insertBefore = null
                if (tagId != null) tagRepository.setFeedTag(result.value.id, tagId, attached = true)
            }
            result
        }
        val successCount = results.count { it is Result.Ok }
        return SubscribeOutcome(
            successCount = successCount,
            failCount = results.size - successCount,
            firstError = results.filterIsInstance<Result.Err>().firstOrNull()?.exception,
        )
    }
}
