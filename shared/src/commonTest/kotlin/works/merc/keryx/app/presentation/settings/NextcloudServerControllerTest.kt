package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudServerControllerTest {

    private val probed = mutableListOf<String>()

    private fun controller(initialUrl: String = "", probe: suspend (String) -> Result<Unit> = { Result.Ok(Unit) }) =
        NextcloudServerController(probe = { url -> probed += url; probe(url) }, initialUrl = initialUrl)

    @Test
    fun startsFromTheInitialUrl() {
        val c = controller(initialUrl = "https://cloud.example.com")
        assertEquals("https://cloud.example.com", c.state.value.url)
        assertTrue(c.state.value.confirmEnabled)
    }

    @Test
    fun aBlankAddressCannotBeConfirmed() = runTest {
        val c = controller(initialUrl = "  ")
        assertFalse(c.state.value.confirmEnabled)
        assertNull(c.submit())
        assertTrue(probed.isEmpty())
    }

    @Test
    fun aServerThatPassesYieldsTheNormalizedRequest() = runTest {
        val c = controller()
        c.setUrl("cloud.example.com/nextcloud/index.php/login")

        val request = c.submit()

        assertEquals("https://cloud.example.com/nextcloud", request?.serverUrl)
        assertEquals(listOf("https://cloud.example.com/nextcloud"), probed)
        assertFalse(c.state.value.checking)
        assertNull(c.state.value.error)
    }

    @Test
    fun aNonHttpsAddressIsRejectedWithoutProbing() = runTest {
        val c = controller(initialUrl = "http://cloud.example.com")

        assertNull(c.submit())

        assertEquals(ErrorKind.NEXTCLOUD_HTTPS_REQUIRED, c.state.value.error)
        assertTrue(probed.isEmpty())
    }

    @Test
    fun aFailedProbeShowsItsReason() = runTest {
        val c = controller(initialUrl = "cloud.example.com") {
            Result.Err(NextcloudServerException(NextcloudServerProblem.UNREACHABLE, "offline"))
        }

        assertNull(c.submit())

        assertEquals(ErrorKind.NEXTCLOUD_UNREACHABLE, c.state.value.error)
        assertFalse(c.state.value.checking)
    }

    @Test
    fun editingClearsTheError() = runTest {
        val c = controller(initialUrl = "http://x")
        c.submit()
        c.setUrl("https://x")
        assertNull(c.state.value.error)
    }

    @Test
    fun aSecondSubmitWhileCheckingDoesNothingAndAnEditDuringTheCheckDropsItsResult() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val c = controller(initialUrl = "cloud.example.com") { gate.await() }

        val first = async { c.submit() }
        runCurrent()
        assertTrue(c.state.value.checking)
        assertFalse(c.state.value.confirmEnabled)
        assertNull(c.submit())

        c.setUrl("other.example.com")
        gate.complete(Result.Ok(Unit))

        assertNull(first.await())
        assertEquals("other.example.com", c.state.value.url)
        assertFalse(c.state.value.checking)
        assertEquals(1, probed.size)
    }

    @Test
    fun aCancelledCheckEndsSoTheAddressCanBeConfirmedAgain() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val c = controller(initialUrl = "cloud.example.com") { gate.await() }

        val first = async { c.submit() }
        runCurrent()
        assertTrue(c.state.value.checking)

        first.cancel()
        runCurrent()

        assertTrue(first.isCancelled)
        assertFalse(c.state.value.checking)
        assertTrue(c.state.value.confirmEnabled)
        assertNull(c.state.value.error)
        assertEquals("cloud.example.com", c.state.value.url)
    }

    @Test
    fun aCheckCancelledAfterAnEditKeepsTheEditedAddress() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        val c = controller(initialUrl = "cloud.example.com") { gate.await() }

        val first = async { c.submit() }
        runCurrent()
        c.setUrl("other.example.com")
        first.cancel()
        runCurrent()

        assertFalse(c.state.value.checking)
        assertEquals("other.example.com", c.state.value.url)
        assertTrue(c.state.value.confirmEnabled)
    }
}
