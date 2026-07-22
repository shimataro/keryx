package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomUriRedirectTransportTest {

    /**
     * Regression guard for the emit-before-subscribe race: a fast redirect can deliver the callback
     * the instant the browser is launched. Because the transport now launches the browser from
     * onSubscription (after subscribing), an emission that happens during launch must still be
     * captured rather than lost to the replay=0 flow.
     */
    @Test
    fun capturesCallbackEmittedDuringBrowserLaunch() = runTest {
        val flow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val transport = CustomUriRedirectTransport(flow, redirectUri = "keryx://oauth2/callback")

        val result = transport.capture(state = "st8", timeoutMillis = 10_000) {
            // Simulate an instant redirect: emit before returning from the browser launch.
            flow.emit(OAuthCallbackParams(code = "the-code", state = "st8", error = null))
        }

        assertEquals("the-code", result?.code)
        assertEquals("st8", result?.state)
    }

    @Test
    fun ignoresCallbackWithMismatchedStateAndTimesOut() = runTest {
        val flow = MutableSharedFlow<OAuthCallbackParams>(replay = 0, extraBufferCapacity = 1)
        val transport = CustomUriRedirectTransport(flow, redirectUri = "keryx://oauth2/callback")

        val result = transport.capture(state = "expected", timeoutMillis = 1_000) {
            flow.emit(OAuthCallbackParams(code = "c", state = "different", error = null))
        }

        assertNull(result)
    }
}
