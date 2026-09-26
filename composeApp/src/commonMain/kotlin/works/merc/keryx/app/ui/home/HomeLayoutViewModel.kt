package works.merc.keryx.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.PANE_WIDTH_PERSIST_DEBOUNCE_MS
import works.merc.keryx.app.domain.SettingsRepository

/**
 * The Compose home screen's own layout state — the resizable pane widths and which pane had focus —
 * persisted in `local_settings.json`. Split out of the shared
 * [works.merc.keryx.app.presentation.home.HomeViewModel] because pane layout is per UI: the Apple
 * app lays out and restores its columns through `NavigationSplitView` and its own scene storage
 * (see `docs/app-architecture.md`'s "Apple Native Apps (SwiftUI)").
 */
@OptIn(FlowPreview::class)
class HomeLayoutViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _feedListPaneWidth = MutableStateFlow(
        settingsRepository.getLocalSettings().feedListPaneWidth
            .coerceIn(FEED_LIST_PANE_MIN_WIDTH.toDouble(), FEED_LIST_PANE_MAX_WIDTH.toDouble()),
    )
    val feedListPaneWidth: StateFlow<Double> = _feedListPaneWidth.asStateFlow()

    private val _articleListPaneWidth = MutableStateFlow(
        settingsRepository.getLocalSettings().articleListPaneWidth
            .coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), ARTICLE_LIST_PANE_MAX_WIDTH.toDouble()),
    )
    val articleListPaneWidth: StateFlow<Double> = _articleListPaneWidth.asStateFlow()

    init {
        combine(_feedListPaneWidth, _articleListPaneWidth) { feed, article -> feed to article }
            .debounce(PANE_WIDTH_PERSIST_DEBOUNCE_MS)
            .onEach { (feed, article) ->
                settingsRepository.mutateLocalSettings { it.copy(feedListPaneWidth = feed, articleListPaneWidth = article) }
            }.launchIn(viewModelScope)
    }

    fun setFeedListPaneWidth(width: Double) {
        _feedListPaneWidth.value = width.coerceIn(FEED_LIST_PANE_MIN_WIDTH.toDouble(), FEED_LIST_PANE_MAX_WIDTH.toDouble())
    }

    fun setArticleListPaneWidth(width: Double) {
        _articleListPaneWidth.value = width.coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), ARTICLE_LIST_PANE_MAX_WIDTH.toDouble())
    }

    /**
     * Retrieves the last focused home pane from local settings.
     *
     * @return The previously focused pane, or [HomePane.ArticleList] when no valid saved pane exists.
     */
    fun getInitialFocusedPane(): HomePane =
        settingsRepository.getLocalSettings().lastFocusedPane
            ?.let { raw -> HomePane.entries.firstOrNull { it.name == raw } }
            ?: HomePane.ArticleList

    /**
     * Sets the pane that should receive focus.
     *
     * @param pane The pane to focus.
     */
    fun setFocusedPane(pane: HomePane) {
        settingsRepository.mutateLocalSettings { it.copy(lastFocusedPane = pane.name) }
    }
}
