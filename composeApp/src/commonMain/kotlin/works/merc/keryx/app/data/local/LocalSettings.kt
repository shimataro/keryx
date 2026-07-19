package works.merc.keryx.app.data.local

import kotlinx.serialization.Serializable
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_WIDTH_DEFAULT
import works.merc.keryx.app.core.FEED_LIST_PANE_WIDTH_DEFAULT

/**
 * Device-local settings, stored as JSON in `local_settings.json`. NOT synced to
 * the cloud.
 */
@Serializable
data class ArticleScrollPosition(val articleId: String, val scrollOffset: Int)

@Serializable
data class LocalSettings(
    val themeMode: String = "system", // "light" | "dark" | "system"
    val fontSizeScale: Double = 1.0,
    val refreshIntervalMinutes: Int = 30,
    val startMinimized: Boolean = false,
    val cloudStorageType: String? = null, // "dropbox" | null (local only)
    val notificationEnabled: Boolean = true,
    val lastCacheCleanupAt: Long? = null,
    val windowWidth: Double? = null,
    val windowHeight: Double? = null,
    val feedListPaneWidth: Double = FEED_LIST_PANE_WIDTH_DEFAULT.toDouble(),
    val articleListPaneWidth: Double = ARTICLE_LIST_PANE_WIDTH_DEFAULT.toDouble(),
    val collapsedFolderIds: Set<String> = emptySet(),
    val lastFilter: String? = null,
    val lastArticleId: String? = null,
    val recentArticleScrollPositions: List<ArticleScrollPosition> = emptyList(),
    val lastFocusedPane: String? = null,
    val lastUnreadOnly: Boolean? = null,
    val lastNewestFirst: Boolean? = null,
    /** 0 = startup checks only, no periodic recheck (see [works.merc.keryx.app.domain.shouldCheckForUpdate]). */
    val updateCheckIntervalHours: Int = 24,
    val lastUpdateCheckAt: Long? = null,
    /** Last time the full FTS index was rebuilt (healing pass); gates the once-per-24h auto rebuild. */
    val lastFtsRebuiltAt: Long? = null,
)
