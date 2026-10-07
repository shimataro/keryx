package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.data.local.db.Articles
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.ArticleListRow
import works.merc.keryx.app.domain.toListRow
import works.merc.keryx.app.domain.toReaderRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The size every case lays the pane out at. */
private val PANE_TEST_SIZE = DpSize(400.dp, 500.dp)

/**
 * `ArticleDetailPaneContent` composes its native reader unconditionally, regardless of whether an
 * article is selected — deliberately, since mounting/unmounting that heavyweight AWT surface is
 * what causes the whole-window flicker documented in `docs/known-issues.md`. These tests exercise
 * the Compose-only shell around it with a stub [reader] slot, since the real reader is a genuine
 * native OS browser view a Compose UI test's offscreen renderer cannot host.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleDetailPaneTest {

    @Test
    fun readerBoundsAreIdenticalWithAndWithoutASelection() = runDesktopComposeUiTest {
        var article by mutableStateOf<Articles?>(null)

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        val emptyBounds = onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        article = testArticle()
        waitForIdle()
        val selectedBounds = onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertEquals(emptyBounds, selectedBounds)
    }

    @Test
    fun readerStaysComposedWithNoArticleSelected() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun starAndMarkUnreadAreDisabledWithNoSelectionAndEnabledWithASelection() = runDesktopComposeUiTest {
        var article by mutableStateOf<Articles?>(null)

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        onNodeWithContentDescription("スター").assertIsNotEnabled()
        onNodeWithContentDescription("未読に戻す").assertIsNotEnabled()

        article = testArticle()
        waitForIdle()
        onNodeWithContentDescription("スター").assertIsEnabled()
        onNodeWithContentDescription("未読に戻す").assertIsEnabled()
    }

    @Test
    fun readButtonNamesTheActionItPerformsAndFlipsWithTheArticlesReadState() = runDesktopComposeUiTest {
        var article by mutableStateOf(testArticle(isRead = 1L))
        var toggles = 0

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                onToggleRead = { toggles++ },
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        onNodeWithContentDescription("既読にする").assertDoesNotExist()

        onNodeWithContentDescription("未読に戻す").performClick()
        assertEquals(1, toggles)

        // The article turned unread: the same button now offers the way back.
        article = testArticle(isRead = 0L)
        waitForIdle()
        onNodeWithContentDescription("未読に戻す").assertDoesNotExist()
        onNodeWithContentDescription("既読にする").performClick()
        assertEquals(2, toggles)
    }

    /**
     * The button follows `shownUnread`, not the article's own flag: while a newly selected article loads, the
     * pane still holds the previous (unread) one, and the button must already show the read state.
     */
    @Test
    fun readButtonFollowsShownUnreadRatherThanTheArticlesOwnFlag() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(isRead = 0L),
                shownUnread = false,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("未読に戻す").assertExists()
        onNodeWithContentDescription("既読にする").assertDoesNotExist()
    }

    @Test
    fun copyAndOpenAreDisabledForASelectedArticleWithNoUrl() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(url = ""),
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertIsNotEnabled()
        onNodeWithContentDescription("ブラウザーで開く").assertIsNotEnabled()
    }

    @Test
    fun openInBrowserButtonIsDisabledForANonHttpUrlWhileCopyStaysEnabled() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(url = "file:///etc/passwd"),
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertIsEnabled()
        onNodeWithContentDescription("ブラウザーで開く").assertIsNotEnabled()
    }

    @Test
    fun openInBrowserButtonInvokesTheSharedHandlerWithTheDisplayedArticle() = runDesktopComposeUiTest {
        val article = testArticle()
        val opened = mutableListOf<Articles>()
        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                onOpenInBrowser = { opened += it },
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("ブラウザーで開く").performClick()
        waitForIdle()

        assertEquals(listOf(article), opened)
    }

    @Test
    fun copyAndOpenAreVisibleButDisabledWithNoSelection() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertIsNotEnabled()
        onNodeWithContentDescription("ブラウザーで開く").assertIsNotEnabled()
    }

    @Test
    fun blankContentFallsBackToSummaryInsteadOfShowingNoContentNotice() = runDesktopComposeUiTest {
        var capturedBody: String? = null

        setContent {
            ArticleDetailPaneContent(
                article = testArticle(content = "   ", summary = "fallback summary"),
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, body, _, _ -> capturedBody = body; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals("fallback summary", capturedBody)
    }

    @Test
    fun readerReceivesTheArticleUrl() = runDesktopComposeUiTest {
        var capturedArticleUrl: String? = "unset"
        val article = testArticle()

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, articleUrl, _ -> capturedArticleUrl = articleUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(article.url, capturedArticleUrl)
    }

    @Test
    fun readerReceivesNullArticleUrlWhenNoArticleSelected() = runDesktopComposeUiTest {
        var capturedArticleUrl: String? = "unset"

        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, articleUrl, _ -> capturedArticleUrl = articleUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(null, capturedArticleUrl)
    }

    @Test
    fun copyButtonInvokesTheSharedCopyHandlerWithTheDisplayedArticle() = runDesktopComposeUiTest {
        val article = testArticle()
        val copied = mutableListOf<Articles>()
        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                onCopyUrl = { copied += it },
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").performClick()
        waitForIdle()

        assertEquals(listOf(article), copied)
    }

    @Test
    fun withoutAShareHandlerTheToolbarHasNoShareButton() = runDesktopComposeUiTest {
        // Desktop: no share sheet, so HomeScreen passes no handler.
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("共有").assertDoesNotExist()
    }

    @Test
    fun shareButtonInvokesTheSharedHandlerWithTheDisplayedArticle() = runDesktopComposeUiTest {
        val article = testArticle()
        val shared = mutableListOf<Articles>()
        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                onShare = { shared += it },
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("共有").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(listOf(article), shared)
    }

    @Test
    fun shareButtonIsDisabledWithoutAnArticleOrAUsableUrl() = runDesktopComposeUiTest {
        var article by mutableStateOf<Articles?>(null)
        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(PANE_TEST_SIZE),
                onShare = {},
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        onNodeWithContentDescription("共有").assertIsNotEnabled()

        article = testArticle(url = " ")
        waitForIdle()
        onNodeWithContentDescription("共有").assertIsNotEnabled()
    }

    @Test
    fun copyButtonDoesNotShowASnackbarItself() = runDesktopComposeUiTest {
        // The snackbar belongs to the shared ArticleUrlCopier, so it appears for every copy route;
        // the reader showing one too would double it for a copy made from its own button.
        val snackbarHostState = SnackbarHostState()
        setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                ArticleDetailPaneContent(
                    article = testArticle(),
                    modifier = Modifier.size(PANE_TEST_SIZE),
                    reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").performClick()
        waitForIdle()

        assertEquals(null, snackbarHostState.currentSnackbarData)
    }

    @Test
    fun copyingTheUrlDoesNotCrashWithNoHostProvided() = runDesktopComposeUiTest {
        // LocalSnackbarHostState defaults to null (desktop's own steady state — see its KDoc). The
        // ✓ comes from the pulse the shared handler raises, wired here as HomeScreen wires it.
        var copyPulse by mutableStateOf(0)
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                copyPulse = copyPulse,
                onCopyUrl = { copyPulse++ },
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").performClick()
        waitForIdle()

        onNodeWithContentDescription("URL をコピーしました").assertExists()
    }

    // --- Copy pulses from other routes (keyboard, menu bar, article-row context menu) ---

    @Test
    fun aPulseRaisedBeforeCompositionDoesNotFlashTheCopiedState() = runDesktopComposeUiTest {
        val snackbarHostState = SnackbarHostState()
        setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                ArticleDetailPaneContent(
                    article = testArticle(),
                    modifier = Modifier.size(PANE_TEST_SIZE),
                    copyPulse = 5,
                    reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertExists()
        assertEquals(null, snackbarHostState.currentSnackbarData)
    }

    @Test
    fun aPulseRaisedWhileComposedFlashesTheCopiedState() = runDesktopComposeUiTest {
        var copyPulse by mutableStateOf(5)
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                copyPulse = copyPulse,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        copyPulse = 6
        waitForIdle()

        onNodeWithContentDescription("URL をコピーしました").assertExists()
    }

    @Test
    fun aPulseRaisedWhileThePaneWasAbsentDoesNotFlashOnReEntry() = runDesktopComposeUiTest {
        val snackbarHostState = SnackbarHostState()
        var copyPulse by mutableStateOf(0)
        var shown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                if (shown) {
                    ArticleDetailPaneContent(
                        article = testArticle(),
                        modifier = Modifier.size(PANE_TEST_SIZE),
                        copyPulse = copyPulse,
                        reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }
        waitForIdle()

        shown = false
        waitForIdle()
        copyPulse = 1
        waitForIdle()
        shown = true
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertExists()
        assertEquals(null, snackbarHostState.currentSnackbarData)
    }

    // --- Swipe-to-navigate accessibility actions (the reader's screen-reader counterpart for
    // articleSwipeNavigation's pointer-only gesture — see ArticleSwipeNav.kt's
    // articleSwipeAccessibilityActions). ---

    /** The labels of the custom accessibility actions exposed by [ARTICLE_READER_TEST_TAG]. */
    private fun androidx.compose.ui.test.ComposeUiTest.readerCustomActionLabels(): List<String> =
        onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
            .map { it.label }

    @Test
    fun swipeAccessibilityActionsAreExposedWhenTouchPrimaryAndNarrow() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = {},
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                isTouchPrimary = true,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(listOf("前の記事", "次の記事"), readerCustomActionLabels())
    }

    @Test
    fun swipeAccessibilityActionsAreAbsentOnDesktopEvenWhenNarrow() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = {},
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                isTouchPrimary = false,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(emptyList(), readerCustomActionLabels())
    }

    @Test
    fun swipeAccessibilityActionsAreAbsentAtTriplePaneWidth() = runDesktopComposeUiTest {
        // swipeNavigation == null is how this codebase signals PaneLayout.Triple for the reader's
        // swipe boundary (see ArticleSwipeNavigation's own KDoc) — onNavigateUp is a separate
        // signal (whether the pane draws a back button) and is left non-null here on purpose, to
        // show the two are independent.
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = {},
                swipeNavigation = null,
                isTouchPrimary = true,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(emptyList(), readerCustomActionLabels())
    }

    @Test
    fun swipeAccessibilityActionsArePresentWithNoBackButton() = runDesktopComposeUiTest {
        // PaneLayout.Dual: the reader is a permanent neighbor of the article list there, like
        // Gmail's own tablet reading pane, so onNavigateUp is null (no back button) — but
        // swipeNavigation is still supplied, since it's an independent signal from onNavigateUp
        // (see ArticleSwipeNavigation's own KDoc). external-spec.md §9 requires the swipe at every
        // narrow layout regardless of whether that layout's reader has a back button.
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = null,
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                isTouchPrimary = true,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(listOf("前の記事", "次の記事"), readerCustomActionLabels())
    }

    @Test
    fun swipeAccessibilityActionsOmitADirectionWithNothingToMoveTo() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = {},
                swipeNavigation = ArticleSwipeNavigation({}, {}, { false }, { true }),
                isTouchPrimary = true,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(listOf("前の記事"), readerCustomActionLabels())
    }

    @Test
    fun invokingTheNextArticleAccessibilityActionCallsOnSelectNext() = runDesktopComposeUiTest {
        var invoked = false
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(PANE_TEST_SIZE),
                onNavigateUp = {},
                swipeNavigation = ArticleSwipeNavigation({ invoked = true }, {}, { true }, { true }),
                isTouchPrimary = true,
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        val actions = onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        val nextAction = actions.first { it.label == "次の記事" }
        assertTrue(nextAction.action(), "custom action \"次の記事\" reported failure")
        assertTrue(invoked)
    }

    // --- Pager form (narrow layouts) ---

    /**
     * The pager keeps the pages either side of the one on screen composed
     * (`beyondViewportPageCount = 1`), which is the whole reason a swipe back lands on the previous
     * article still scrolled where it was left: its WebView was never torn down.
     */
    @Test
    fun theReaderPagerKeepsTheNeighbouringArticlesComposed() = runDesktopComposeUiTest {
        val articles = listOf(testArticle("a1"), testArticle("a2"), testArticle("a3"))
        val rendered = mutableSetOf<String>()

        setContent {
            ArticleDetailPaneContent(
                article = articles[1],
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = true,
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                readerPaging = pagingFor(articles, selected = articles[1]),
                reader = { _, _, url, _ -> url?.let { rendered += it }; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(articles.map { it.url }.toSet(), rendered)
    }

    /**
     * A page whose body has not been read from the DB yet still renders its own header, so swiping
     * towards an article never shows a blank pane while the lookup is in flight — and it must not
     * claim the article has no content, which is a different state with a different message.
     */
    @Test
    fun aPageWhoseBodyHasNotLoadedYetStillRendersItsHeader() = runDesktopComposeUiTest {
        val selected = testArticle("a1", title = "Selected")
        val neighbour = testArticle("a2", title = "Not loaded yet")
        val htmlByUrl = mutableMapOf<String?, String>()

        setContent {
            ArticleDetailPaneContent(
                article = selected,
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = true,
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                // Only the selected article's own body is supplied, mirroring the moment a page
                // composes and HomeViewModel.requestArticleContent has not returned yet.
                readerPaging = pagingFor(listOf(selected, neighbour), selected = selected),
                reader = { html, _, url, _ -> htmlByUrl[url] = html; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        val pending = requireNotNull(htmlByUrl[neighbour.url])
        assertContains(pending, "Not loaded yet")
        assertContains(htmlByUrl.getValue(selected.url), "<p>content</p>")
    }

    /**
     * `PaneLayout.Triple` (every desktop window, and a wide tablet in landscape) keeps the single
     * unconditionally-composed reader it has always had — see this file's own header for why that
     * surface must never be mounted conditionally there.
     */
    @Test
    fun noPagerIsComposedWhenTheCallerSuppliedNoPaging() = runDesktopComposeUiTest {
        var readerCalls = 0

        setContent {
            ArticleDetailPaneContent(
                article = testArticle("a1"),
                modifier = Modifier.size(PANE_TEST_SIZE),
                reader = { _, _, _, _ -> readerCalls++; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(1, readerCalls)
    }

    /**
     * Regression test for the invariant this file's own header states: the heavyweight pager must
     * never mount on a platform whose WebView is the interop AWT surface, even if a caller supplies
     * `readerPaging`. `isTouchPrimary` is checked here too, not just by `ArticleDetailPane`'s own
     * conditional construction of `readerPaging` — this is the composable that actually decides
     * whether the pager mounts.
     */
    @Test
    fun thePagerNeverMountsWhenNotTouchPrimaryEvenIfPagingWasSupplied() = runDesktopComposeUiTest {
        val articles = listOf(testArticle("a1"), testArticle("a2"))
        var readerCalls = 0

        setContent {
            ArticleDetailPaneContent(
                article = articles[0],
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = false,
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                readerPaging = pagingFor(articles, selected = articles[0]),
                reader = { _, _, _, _ -> readerCalls++; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        // Exactly one call — the single, unconditionally-composed reader — not the pager's own
        // per-page calls (2, one for each page).
        assertEquals(1, readerCalls)
    }
    /**
     * On a touch-primary platform the feed is named in the article's own byline rather than the
     * toolbar, which has no room for it beside the back button and four actions at phone width.
     */
    @Test
    fun touchPrimaryNamesTheFeedInTheBylineNotTheToolbar() = runDesktopComposeUiTest {
        var html = ""
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                feedName = "Feed f1",
                feedsById = mapOf("f1" to testFeed("f1", faviconUrl = "https://example.com/icon.png")),
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = true,
                reader = { h, _, _, _ -> html = h; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithText("Feed f1").assertDoesNotExist()
        assertContains(html, "Feed f1")
        assertContains(html, "https://example.com/icon.png")
    }

    @Test
    fun desktopKeepsTheFeedNameInTheToolbarAndOutOfTheByline() = runDesktopComposeUiTest {
        var html = ""
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                feedName = "Feed f1",
                feedsById = mapOf("f1" to testFeed("f1", faviconUrl = "https://example.com/icon.png")),
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = false,
                reader = { h, _, _, _ -> html = h; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithText("Feed f1").assertExists()
        assertFalse("Feed f1" in html)
        assertFalse("https://example.com/icon.png" in html)
    }

    /** Each pager page resolves its own feed, so a neighbour in a mixed list names its own feed. */
    @Test
    fun eachPagerPageNamesItsOwnFeedInItsByline() = runDesktopComposeUiTest {
        val first = testArticle("a1", feedId = "f1")
        val second = testArticle("a2", feedId = "f2")
        val htmlByUrl = mutableMapOf<String?, String>()

        setContent {
            ArticleDetailPaneContent(
                article = first,
                feedsById = mapOf("f1" to testFeed("f1"), "f2" to testFeed("f2")),
                modifier = Modifier.size(PANE_TEST_SIZE),
                isTouchPrimary = true,
                swipeNavigation = ArticleSwipeNavigation({}, {}, { true }, { true }),
                readerPaging = pagingFor(listOf(first, second), selected = first, hydrated = listOf(first, second)),
                reader = { h, _, url, _ -> htmlByUrl[url] = h; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        val firstHtml = htmlByUrl.getValue(first.url)
        val secondHtml = htmlByUrl.getValue(second.url)
        assertContains(firstHtml, "Feed f1")
        assertFalse("Feed f2" in firstHtml)
        assertContains(secondHtml, "Feed f2")
        assertFalse("Feed f1" in secondHtml)
    }
}

/**
 * An [ArticleReaderPaging] over [articles], with every body already hydrated except where
 * [selected] is the only one supplied — the caller decides by passing a shorter list to
 * [ArticleReaderPaging.contents] through this helper's own [hydrated] parameter.
 */
private fun pagingFor(
    articles: List<Articles>,
    selected: Articles,
    hydrated: List<Articles> = listOf(selected),
): ArticleReaderPaging = ArticleReaderPaging(
    pages = articles.map { it.toListRow() },
    contents = hydrated.associate { it.id to it.toReaderRow() },
    requestContent = {},
    onPageSettled = {},
)

private fun testArticle(
    id: String = "a1",
    url: String = "https://example.com/$id",
    title: String = "Article $id",
    content: String? = "<p>content</p>",
    summary: String? = null,
    isStarred: Long = 0L,
    isRead: Long = 1L,
    feedId: String = "f1",
): Articles = Articles(
    id = id,
    feed_id = feedId,
    guid = "g$id",
    url = url,
    title = title,
    summary = summary,
    content = content,
    author = null,
    published_at = 1_754_000_000_000L,
    thumbnail_url = null,
    is_read = isRead,
    read_at = null,
    is_starred = isStarred,
    starred_at = null,
    cached_at = 0L,
    search_text = "",
    updated_at = 0L,
    created_at = 0L,
    deleted_at = null,
    deleted_updated_at = null,
)

private fun testFeed(id: String, faviconUrl: String? = null): Feeds = Feeds(
    id = id,
    url = "https://example.com/$id.xml",
    site_url = null,
    title = "Feed $id",
    description = null,
    favicon_url = faviconUrl,
    etag = null,
    last_modified = null,
    error_count = 0L,
    last_error = null,
    custom_title = null,
    folder_id = null,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = 0L,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
