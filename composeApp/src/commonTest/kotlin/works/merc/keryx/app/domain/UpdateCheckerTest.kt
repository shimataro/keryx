package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateCheckerTest {

    private fun checker(currentVersion: String = "1.0.0", body: String, status: HttpStatusCode = HttpStatusCode.OK): UpdateChecker {
        val client = HttpClient(MockEngine { if (status == HttpStatusCode.OK) respond(body) else respondError(status) }) {
            expectSuccess = false
        }
        return UpdateChecker(client, currentVersion, repoSlug = "owner/repo")
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        val status = checker(body = """{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0"}""").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun newerTagIsAvailableWithVPrefixStripped() = runTest {
        val status = checker(body = """{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3"}""").check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.2.3", status.version)
        assertEquals("https://ex.com/1.2.3", status.url)
    }

    @Test
    fun olderTagIsUpToDateNotDowngradeAvailable() = runTest {
        // A dev build ahead of the latest published release must never report "Available".
        val status = checker(currentVersion = "2.0.0", body = """{"tag_name":"v1.0.0","html_url":"https://ex.com"}""").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun nonSuccessStatusIsFailed() = runTest {
        val status = checker(body = "", status = HttpStatusCode.NotFound).check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun malformedJsonIsFailed() = runTest {
        val status = checker(body = "not json").check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun missingFieldsAreFailed() = runTest {
        val status = checker(body = """{"unrelated":"field"}""").check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun cancellationPropagatesNotConvertedToFailed() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            started.complete(Unit)
            gate.await()
            respond("""[{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3"}]""")
        }) {
            expectSuccess = false
        }
        val checker = UpdateChecker(client, "1.0.0", repoSlug = "owner/repo")
        var status: UpdateStatus? = null
        val job = launch { status = checker.check() }
        runCurrent()
        started.await()
        job.cancel()
        job.join()
        assertNull(status)
    }
}

class IsNewerTest {

    @Test
    fun strictlyGreaterIsNewer() {
        assertEquals(true, isNewer("1.2.0", "1.1.9"))
        assertEquals(true, isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun equalOrLesserIsNotNewer() {
        assertEquals(false, isNewer("1.0.0", "1.0.0"))
        assertEquals(false, isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun differingSegmentCountsCompareCorrectly() {
        assertEquals(false, isNewer("1.0", "1.0.0"))
        assertEquals(true, isNewer("1.0.1", "1.0"))
    }

    @Test
    fun unparseableSegmentsAreNotNewer() {
        assertEquals(false, isNewer("abc", "1.0.0"))
    }
}

class ShouldCheckForUpdateTest {

    @Test
    fun zeroIntervalNeverDue() {
        assertEquals(false, shouldCheckForUpdate(nowMillis = 1_000_000L, lastCheckMillis = null, intervalHours = 0))
        assertEquals(false, shouldCheckForUpdate(nowMillis = 1_000_000L, lastCheckMillis = 0L, intervalHours = 0))
    }

    @Test
    fun neverCheckedIsDue() {
        assertEquals(true, shouldCheckForUpdate(nowMillis = 1_000_000L, lastCheckMillis = null, intervalHours = 24))
    }

    @Test
    fun exactlyAtBoundaryIsDue() {
        val hourMs = 3_600_000L
        assertEquals(true, shouldCheckForUpdate(nowMillis = 24 * hourMs, lastCheckMillis = 0L, intervalHours = 24))
    }

    @Test
    fun justBeforeBoundaryIsNotDue() {
        val hourMs = 3_600_000L
        assertEquals(false, shouldCheckForUpdate(nowMillis = 24 * hourMs - 1, lastCheckMillis = 0L, intervalHours = 24))
    }
}
