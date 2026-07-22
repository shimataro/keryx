package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateCheckerTest {

    private fun checker(currentVersion: String = "1.0.0", body: String, status: HttpStatusCode = HttpStatusCode.OK): UpdateChecker {
        val client = HttpClient(MockEngine { if (status == HttpStatusCode.OK) respond(body) else respondError(status) }) {
            expectSuccess = false
        }
        return UpdateChecker(client, currentVersion, repoSlug = "owner/repo")
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        val status = checker(body = """[{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0"}]""").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun newerTagIsAvailableWithVPrefixStripped() = runTest {
        val status = checker(body = """[{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3"}]""").check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.2.3", status.version)
        assertEquals("https://ex.com/1.2.3", status.url)
    }

    @Test
    fun olderTagIsUpToDateNotDowngradeAvailable() = runTest {
        // A dev build ahead of the latest published release must never report "Available".
        val status = checker(currentVersion = "2.0.0", body = """[{"tag_name":"v1.0.0","html_url":"https://ex.com"}]""").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun preReleaseBelowStableIsAvailableWhileCurrentIsPreStable() = runTest {
        // Both this build (0.0.9) and the release (0.1.0) are below 1.0.0 → the pre-release is offered.
        val status = checker(
            currentVersion = "0.0.9",
            body = """[{"tag_name":"v0.1.0","html_url":"https://ex.com/0.1.0","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.1.0", status.version)
    }

    @Test
    fun suffixedPreReleaseBelowStableIsAvailableWhileCurrentIsPreStable() = runTest {
        // A conventional prerelease suffix (-beta) must not defeat the version comparison:
        // 0.1.0-beta is a higher core than 0.0.9 → the eligible prerelease is offered.
        val status = checker(
            currentVersion = "0.0.9",
            body = """[{"tag_name":"v0.1.0-beta","html_url":"https://ex.com/0.1.0-beta","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.1.0-beta", status.version)
    }

    @Test
    fun highestSuffixedPreReleaseIsSelected() = runTest {
        // Among eligible suffixed prereleases with equal cores, the highest identifier wins.
        val status = checker(
            currentVersion = "0.0.9",
            body = """[
                {"tag_name":"v0.1.0-alpha","html_url":"https://ex.com/0.1.0-alpha","prerelease":true},
                {"tag_name":"v0.1.0-beta","html_url":"https://ex.com/0.1.0-beta","prerelease":true}
            ]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.1.0-beta", status.version)
    }

    @Test
    fun preReleaseIgnoredOnceCurrentIsStable() = runTest {
        // A 1.0.0+ build never gets pre-releases, even a below-1.0.0 one → no eligible release.
        val status = checker(
            currentVersion = "1.0.0",
            body = """[{"tag_name":"v0.9.0","html_url":"https://ex.com/0.9.0","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun preReleaseAtOrAboveStableIsIgnored() = runTest {
        // A pre-release whose version is >= 1.0.0 is never eligible, regardless of current version.
        val status = checker(
            currentVersion = "0.9.0",
            body = """[{"tag_name":"v1.0.0-rc1","html_url":"https://ex.com/1.0.0-rc1","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun officialStableReleaseIsAvailable() = runTest {
        val status = checker(
            currentVersion = "1.0.0",
            body = """[{"tag_name":"v1.1.0","html_url":"https://ex.com/1.1.0","prerelease":false}]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.1.0", status.version)
    }

    @Test
    fun rejectedPreReleaseDoesNotHideEligibleStable() = runTest {
        // Newest-first: a 1.1.0 pre-release (>= 1.0.0, rejected) precedes the 1.0.0 stable → 1.0.0 wins.
        val status = checker(
            currentVersion = "0.9.0",
            body = """[
                {"tag_name":"v1.1.0-beta","html_url":"https://ex.com/1.1.0-beta","prerelease":true},
                {"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0","prerelease":false}
            ]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.0.0", status.version)
    }

    @Test
    fun draftIsIgnored() = runTest {
        val status = checker(
            currentVersion = "1.0.0",
            body = """[{"tag_name":"v2.0.0","html_url":"https://ex.com/2.0.0","draft":true}]""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun emptyListIsUpToDate() = runTest {
        val status = checker(body = "[]").check()
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
    fun missingHtmlUrlIsFailed() = runTest {
        val status = checker(body = """[{"tag_name":"v2.0.0"}]""").check()
        assertIs<UpdateStatus.Failed>(status)
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

    @Test
    fun prereleaseSuffixesCompareBySemVer() {
        assertEquals(true, isNewer("0.1.0-beta", "0.0.9"))       // higher core wins over suffix
        assertEquals(false, isNewer("0.1.0-beta", "0.1.0"))      // prerelease < its release
        assertEquals(true, isNewer("0.1.0", "0.1.0-beta"))       // release > prerelease
        assertEquals(true, isNewer("0.1.0-beta", "0.1.0-alpha")) // identifier order (beta > alpha)
        assertEquals(true, isNewer("0.1.0-beta.2", "0.1.0-beta.1")) // dotted numeric identifiers
        assertEquals(false, isNewer("0.1.0-rc1", "0.1.0-rc1"))   // equal
    }
}

class IsBelowStableTest {

    @Test
    fun majorZeroIsBelowStable() {
        assertEquals(true, isBelowStable("0.9.9"))
        assertEquals(true, isBelowStable("0.0.0"))
    }

    @Test
    fun majorOneOrAboveIsNotBelowStable() {
        assertEquals(false, isBelowStable("1.0.0"))
        assertEquals(false, isBelowStable("1.0.0-rc1"))
        assertEquals(false, isBelowStable("2.3.4"))
    }

    @Test
    fun unparseableOrNullIsNotBelowStable() {
        assertEquals(false, isBelowStable("abc"))
        assertEquals(false, isBelowStable(null))
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
