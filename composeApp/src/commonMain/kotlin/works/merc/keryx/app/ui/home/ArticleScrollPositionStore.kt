package works.merc.keryx.app.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import works.merc.keryx.app.core.MAX_REMEMBERED_SCROLL_POSITIONS
import works.merc.keryx.app.data.local.ArticleScrollPosition
import works.merc.keryx.app.domain.SettingsRepository

/**
 * Remembers each article's last scroll offset (MRU-capped), split out of [HomeViewModel] to keep
 * its surface smaller.
 */
class ArticleScrollPositionStore(private val settingsRepository: SettingsRepository) {
    private val _scrollPositions = MutableStateFlow(
        settingsRepository.getLocalSettings().recentArticleScrollPositions,
    )

    /**
         * Retrieves the remembered scroll offset for an article.
         *
         * @param articleId The identifier of the article.
         * @return The remembered scroll offset, or `0` if no position is stored.
         */
        fun getScrollPosition(articleId: String): Int =
        _scrollPositions.value.firstOrNull { it.articleId == articleId }?.scrollOffset ?: 0

    /**
     * Saves the scroll offset for an article and retains only the most recent remembered positions.
     *
     * @param articleId The identifier of the article.
     * @param offset The article's scroll offset.
     */
    fun saveScrollPosition(articleId: String, offset: Int) {
        val updated = (
            listOf(ArticleScrollPosition(articleId, offset)) +
                _scrollPositions.value.filter { it.articleId != articleId }
            ).take(MAX_REMEMBERED_SCROLL_POSITIONS)
        _scrollPositions.value = updated
        settingsRepository.mutateLocalSettings { it.copy(recentArticleScrollPositions = updated) }
    }
}
