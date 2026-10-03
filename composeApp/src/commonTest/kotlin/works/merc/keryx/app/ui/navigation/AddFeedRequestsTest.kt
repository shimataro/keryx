package works.merc.keryx.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AddFeedRequests] carries a link shared from another app to the Add feed dialog: it must never
 * open the dialog over first-run Setup, while still delivering a link shared there once Home shows.
 */
class AddFeedRequestsTest {

    @Test
    fun aRequestMadeDuringSetupIsHeldUntilHome() {
        val requests = AddFeedRequests()
        requests.request("https://example.com/feed")

        assertNull(requests.release(Screen.Setup), "the dialog must never open over Setup")
        assertNull(requests.release(Screen.Setup), "still held across recompositions on Setup")
        assertEquals("https://example.com/feed", requests.pending.value, "the request stays latched")

        assertEquals("https://example.com/feed", requests.release(Screen.Home))
    }

    @Test
    fun releasingClearsTheRequestSoTheDialogOpensOnlyOnce() {
        val requests = AddFeedRequests()
        requests.request("https://example.com/feed")

        assertEquals("https://example.com/feed", requests.release(Screen.Home))
        assertNull(requests.pending.value)
        assertNull(requests.release(Screen.Home))
    }

    @Test
    fun theLastRequestWins() {
        val requests = AddFeedRequests()
        requests.request("https://first.example/feed")
        requests.request("https://second.example/feed")

        assertEquals("https://second.example/feed", requests.release(Screen.Home))
        assertNull(requests.release(Screen.Home))
    }

    @Test
    fun theSameUrlSharedAgainAfterReleaseIsDeliveredAgain() {
        val requests = AddFeedRequests()
        requests.request("https://example.com/feed")
        requests.release(Screen.Home)

        requests.request("https://example.com/feed")

        assertEquals("https://example.com/feed", requests.release(Screen.Home))
    }

    @Test
    fun nothingIsReleasedWithoutARequest() {
        assertNull(AddFeedRequests().release(Screen.Home))
    }
}
