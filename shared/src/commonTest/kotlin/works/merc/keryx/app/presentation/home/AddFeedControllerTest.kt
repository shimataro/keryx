package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.domain.AddFeedPreview
import works.merc.keryx.app.domain.SubscribeOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddFeedControllerTest {

    private val single = AddFeedPreview.Single(resolvedUrl = "https://ex.com/feed.xml", title = "Ex", articleCount = 3)
    private val multiple = AddFeedPreview.Multiple(
        candidates = listOf(DiscoveredFeedLink("https://ex.com/a.xml"), DiscoveredFeedLink("https://ex.com/b.xml")),
    )

    private fun outcome(ok: Int, failed: Int, error: KeryxException? = null) =
        SubscribeOutcome(successCount = ok, failCount = failed, firstError = error, feedIds = emptyList())

    @Test
    fun confirmIsOnlyEnabledForANonBlankUrlBeforeAPreview() {
        val c = AddFeedController({ single }, { outcome(1, 0) })
        assertFalse(c.state.value.confirmEnabled)
        c.setUrl("   ")
        assertFalse(c.state.value.confirmEnabled)
        c.setUrl("ex.com")
        assertTrue(c.state.value.confirmEnabled)
    }

    @Test
    fun theFirstSubmitPreviewsAndAdoptsTheResolvedUrl() = runTest {
        val c = AddFeedController({ single }, { outcome(1, 0) })
        c.setUrl("ex.com")

        assertFalse(c.submit())

        val s = c.state.value
        assertEquals(single, s.preview)
        assertEquals("https://ex.com/feed.xml", s.url)
        assertTrue(s.hasResult)
        assertNull(s.phase)
    }

    @Test
    fun aMultiplePreviewSelectsEveryCandidateUpFront() = runTest {
        val c = AddFeedController({ multiple }, { outcome(2, 0) })
        c.setUrl("ex.com")
        c.submit()

        assertEquals(setOf("https://ex.com/a.xml", "https://ex.com/b.xml"), c.state.value.selectedCandidates)
        c.clearCandidates()
        assertFalse(c.state.value.confirmEnabled)
        c.toggleCandidate("https://ex.com/b.xml", checked = true)
        assertEquals(setOf("https://ex.com/b.xml"), c.state.value.selectedCandidates)
        c.selectAllCandidates()
        assertEquals(2, c.state.value.selectedCandidates.size)
    }

    @Test
    fun aFailedPreviewShowsItsError() = runTest {
        val error = FeedParseException("not a feed")
        val c = AddFeedController({ AddFeedPreview.Failed(error) }, { outcome(0, 0) })
        c.setUrl("ex.com")
        c.submit()

        assertEquals(error, c.state.value.error)
        assertNull(c.state.value.preview)
    }

    @Test
    fun theSecondSubmitSubscribesAndReportsSuccess() = runTest {
        var subscribed: List<String>? = null
        val c = AddFeedController({ multiple }, { urls -> subscribed = urls; outcome(urls.size, 0) })
        c.setUrl("ex.com")
        c.submit()
        c.toggleCandidate("https://ex.com/a.xml", checked = false)

        assertTrue(c.submit())
        assertEquals(listOf("https://ex.com/b.xml"), subscribed)
    }

    @Test
    fun aPartialSubscribeStaysOpenWithTheCounts() = runTest {
        val c = AddFeedController({ multiple }, { outcome(1, 1) })
        c.setUrl("ex.com")
        c.submit()

        assertFalse(c.submit())
        assertEquals(1 to 1, c.state.value.partialResult)
    }

    @Test
    fun aFailedSubscribeShowsTheFirstError() = runTest {
        val error = FeedTimeoutException()
        val c = AddFeedController({ single }, { outcome(0, 1, error) })
        c.setUrl("ex.com")
        c.submit()

        assertFalse(c.submit())
        assertIs<FeedTimeoutException>(c.state.value.error)
    }

    @Test
    fun editingTheUrlDiscardsThePreviousPreview() = runTest {
        val c = AddFeedController({ single }, { outcome(1, 0) })
        c.setUrl("ex.com")
        c.submit()

        c.setUrl("other.com")

        assertNull(c.state.value.preview)
        assertFalse(c.state.value.hasResult)
    }

    @Test
    fun submitIsANoOpWhileAStepIsInFlightEvenIfTheUrlIsEdited() = runTest {
        val gate = CompletableDeferred<AddFeedPreview>()
        var previews = 0
        val c = AddFeedController({ previews++; gate.await() }, { outcome(1, 0) })
        c.setUrl("ex.com")
        val first = launch { c.submit() }
        testScheduler.advanceUntilIdle()
        assertEquals(AddFeedPhase.Previewing, c.state.value.phase)

        c.setUrl("ex.org")
        assertEquals(AddFeedPhase.Previewing, c.state.value.phase)
        assertFalse(c.submit())
        assertFalse(c.state.value.confirmEnabled)

        gate.complete(single)
        first.join()
        assertEquals(1, previews)
        assertNull(c.state.value.phase)
    }
}
