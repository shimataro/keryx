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
    /**
     * Tags whose attached-feed list is expanded in the sidebar. Tags default to *collapsed*
     * (the opposite of folders, which default to expanded and track the collapsed ones instead),
     * so the sidebar stays as short as it was before this list existed.
     */
    val expandedTagIds: Set<String> = emptySet(),
    val lastFilter: String? = null,
    val lastArticleId: String? = null,
    val recentArticleScrollPositions: List<ArticleScrollPosition> = emptyList(),
    val lastFocusedPane: String? = null,
    val lastUnreadOnly: Boolean? = null,
    /** "Unread only" state scoped to the Starred filter alone, independent of [lastUnreadOnly]. */
    val lastUnreadOnlyStarred: Boolean? = null,
    val lastNewestFirst: Boolean? = null,
    /** 0 = startup checks only, no periodic recheck (see [works.merc.keryx.app.domain.shouldCheckForUpdate]). */
    val updateCheckIntervalHours: Int = 24,
    val lastUpdateCheckAt: Long? = null,
    /** Last time the full FTS index was rebuilt (healing pass); gates the once-per-24h auto rebuild. */
    val lastFtsRebuiltAt: Long? = null,
    /**
     * In-window application menu bar visibility (Linux KDE Global Menu). `null` = auto-decide
     * (shown until this app's `RegisterWindow` succeeds, then hidden); `true`/`false` = explicit
     * user override set via Ctrl+M or the exported "Show Menu Bar" checkbox. Has no effect where no
     * `com.canonical.AppMenu.Registrar` is present (macOS/Windows/non-KDE Linux), where the bar always shows.
     */
    val appMenuBarVisible: Boolean? = null,
)
