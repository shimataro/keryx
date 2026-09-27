package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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

    @Test
    fun concurrentSubmitsStartOnlyOnePreview() = runTest {
        repeat(RACE_ROUNDS) {
            val gate = CompletableDeferred<AddFeedPreview>()
            val calls = MutableStateFlow(0)
            val c = AddFeedController({ calls.update { it + 1 }; gate.await() }, { outcome(1, 0) })
            c.setUrl("ex.com")

            withContext(Dispatchers.Default) {
                val go = CompletableDeferred<Unit>()
                val submits = List(RACERS) { async { go.await(); c.submit() } }
                go.complete(Unit)
                // Every submit that lost the race returns at once; the winner is parked on the gate.
                while (submits.count { it.isCompleted } < RACERS - 1) yield()
                gate.complete(single)
                assertTrue(submits.awaitAll().none { it })
            }

            assertEquals(1, calls.value)
            assertNull(c.state.value.phase)
            assertEquals(single, c.state.value.preview)
        }
    }

    @Test
    fun concurrentSubmitsStartOnlyOneSubscribe() = runTest {
        repeat(RACE_ROUNDS) {
            val gate = CompletableDeferred<SubscribeOutcome>()
            val calls = MutableStateFlow(0)
            val c = AddFeedController({ single }, { calls.update { it + 1 }; gate.await() })
            c.setUrl("ex.com")
            c.submit()

            withContext(Dispatchers.Default) {
                val go = CompletableDeferred<Unit>()
                val submits = List(RACERS) { async { go.await(); c.submit() } }
                go.complete(Unit)
                while (submits.count { it.isCompleted } < RACERS - 1) yield()
                gate.complete(outcome(1, 0))
                assertEquals(1, submits.awaitAll().count { it })
            }

            assertEquals(1, calls.value)
            assertNull(c.state.value.phase)
        }
    }

    /** Starts a preview for "a.com", edits the URL to "b.com" mid-flight, then resolves [stale]. */
    private fun TestScope.previewGoesStaleWhileInFlight(stale: AddFeedPreview): AddFeedController {
        val gate = CompletableDeferred<AddFeedPreview>()
        val requested = mutableListOf<String>()
        val c = AddFeedController({ url -> requested += url; gate.await() }, { outcome(1, 0) })
        c.setUrl("a.com")
        val first = launch { c.submit() }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a.com"), requested)

        c.setUrl("b.com")
        gate.complete(stale)
        testScheduler.advanceUntilIdle()
        assertTrue(first.isCompleted)
        return c
    }

    private fun assertFreshInputFor(url: String, s: AddFeedState) {
        assertEquals(url, s.url)
        assertNull(s.phase)
        assertNull(s.preview)
        assertNull(s.error)
        assertTrue(s.selectedCandidates.isEmpty())
        assertTrue(s.confirmEnabled)
    }

    @Test
    fun aStaleSinglePreviewDoesNotOverwriteTheEditedUrl() = runTest {
        val c = previewGoesStaleWhileInFlight(AddFeedPreview.Single(resolvedUrl = "https://a.com/feed.xml", title = "A", articleCount = 1))
        assertFreshInputFor("b.com", c.state.value)
    }

    @Test
    fun aStaleMultiplePreviewIsDropped() = runTest {
        val c = previewGoesStaleWhileInFlight(multiple)
        assertFreshInputFor("b.com", c.state.value)
    }

    @Test
    fun aStaleFailedPreviewDoesNotShowItsError() = runTest {
        val c = previewGoesStaleWhileInFlight(AddFeedPreview.Failed(FeedParseException("not a feed")))
        assertFreshInputFor("b.com", c.state.value)
    }

    @Test
    fun theEditedUrlCanBePreviewedAfterAStalePreviewIsDropped() = runTest {
        val c = previewGoesStaleWhileInFlight(multiple)
        // The helper's resolver awaits a gate that is already complete, so it returns `multiple` at
        // once — and since "b.com" is still the current URL, this preview applies normally.
        assertFalse(c.submit())
        assertEquals(multiple, c.state.value.preview)
        assertEquals("b.com", c.state.value.url)
    }

    private companion object {
        const val RACERS = 8
        const val RACE_ROUNDS = 50
    }
}
