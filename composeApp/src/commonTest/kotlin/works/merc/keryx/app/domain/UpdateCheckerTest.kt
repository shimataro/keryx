package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.core.compareReleaseVersions
import works.merc.keryx.app.core.isBelowStable
import works.merc.keryx.app.core.isNewer
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {

    private val fakeMacLocation =
        InstallLocation(InstallKind.MAC_APP_BUNDLE, appRoot = "/Applications/Keryx.app", launcherPath = null, parentWritable = true, translocated = false)
    private val fakeUnsupportedLocation =
        InstallLocation(InstallKind.ANDROID_STORE, appRoot = null, launcherPath = null, parentWritable = false, translocated = false)


    /**
     * Builds a checker whose MockEngine routes by request path: `releases/latest` (the stable-build
     * path) uses [latestBody]/[latestStatus], and the `releases` list (the pre-stable path) uses
     * [listBody]/[listStatus]. A test only sets the params for the endpoint its [currentVersion]
     * will actually hit.
     */
    private fun checker(
        currentVersion: String = "1.0.0",
        listBody: String? = null,
        listStatus: HttpStatusCode = HttpStatusCode.OK,
        latestBody: String? = null,
        latestStatus: HttpStatusCode = HttpStatusCode.OK,
        // A fixed, writable macOS install by default — asset-selection tests below rely on this
        // to actually get a non-null UpdateStatus.Available.asset; every pre-existing test in this
        // file only reads .version/.url and is unaffected by which InstallLocation is plugged in.
        location: InstallLocation = fakeMacLocation,
    ): UpdateChecker {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/releases/latest")) {
                if (latestStatus == HttpStatusCode.OK) respond(latestBody ?: "") else respondError(latestStatus)
            } else {
                if (listStatus == HttpStatusCode.OK) respond(listBody ?: "") else respondError(listStatus)
            }
        }) {
            expectSuccess = false
        }
        return UpdateChecker(client, currentVersion, repoSlug = "owner/repo", location = location)
    }

    // --- Stable build (1.0.0+) → releases/latest ---

    @Test
    fun stableCurrentQueriesLatestEndpoint() = runTest {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            respond("""{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0"}""")
        }) { expectSuccess = false }
        UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo").check()
        assertEquals(1, history.size)
        assertEquals("/repos/owner/repo/releases/latest", history[0].url.encodedPath)
    }

    @Test
    fun requestIncludesUserAgentWithAppNameAndVersion() = runTest {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            respond("""{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0"}""")
        }) { expectSuccess = false }
        UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo").check()
        assertEquals(1, history.size)
        assertEquals("$APP_NAME/1.0.0", history[0].headers[HttpHeaders.UserAgent])
    }

    @Test
    fun sameVersionIsUpToDate() = runTest {
        val status = checker(latestBody = """{"tag_name":"v1.0.0","html_url":"https://ex.com/1.0.0"}""").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun newerTagIsAvailableWithVPrefixStripped() = runTest {
        val status = checker(latestBody = """{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3"}""").check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.2.3", status.version)
        assertEquals("https://ex.com/1.2.3", status.url)
    }

    @Test
    fun olderTagIsUpToDateNotDowngradeAvailable() = runTest {
        // A dev build ahead of the latest published release must never report "Available".
        val status = checker(
            currentVersion = "2.0.0",
            latestBody = """{"tag_name":"v1.0.0","html_url":"https://ex.com"}""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun officialStableReleaseIsAvailable() = runTest {
        val status = checker(
            currentVersion = "1.0.0",
            latestBody = """{"tag_name":"v1.1.0","html_url":"https://ex.com/1.1.0","prerelease":false}""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.1.0", status.version)
    }

    @Test
    fun stableCurrentLatest404IsUpToDate() = runTest {
        // releases/latest 404 = the repo has no full release yet (e.g. only pre-releases/drafts).
        // That means "nothing to offer", NOT a failure — a stable build never gets pre-releases.
        val status = checker(currentVersion = "1.0.0", latestStatus = HttpStatusCode.NotFound).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    /**
     * Regression guard: the underlying failure reason (here, the HTTP status) must survive into
     * the returned UpdateStatus.Failed rather than being discarded in favor of a generic message -
     * see UpdateChecker.kt's own UpdateStatus.Failed KDoc and data/remote/ReleaseFeedSource, which
     * is what actually classifies this one.
     */
    @Test
    fun latestServerErrorIsFailed() = runTest {
        val status = checker(currentVersion = "1.0.0", latestStatus = HttpStatusCode.InternalServerError).check()
        assertIs<UpdateStatus.Failed>(status)
        assertEquals(UpdateStage.CHECK, status.exception.stage)
        assertTrue(
            status.exception.messageText.contains("500"),
            "the failure reason must mention the HTTP status, not a generic message: ${status.exception.messageText}",
        )
    }

    @Test
    fun missingHtmlUrlIsFailed() = runTest {
        val status = checker(latestBody = """{"tag_name":"v2.0.0"}""").check()
        assertIs<UpdateStatus.Failed>(status)
        assertEquals(UpdateStage.CHECK, status.exception.stage)
    }

    /**
     * Regression guard: the version this returns ends up as a path component
     * (UpdateRepository.updateDownloadDir), so a tag_name that isn't a plain version string must be
     * rejected outright rather than passed through — see UpdateChecker.kt's own
     * isSafeVersionForPathUse.
     */
    @Test
    fun tagNameWithAPathSeparatorIsFailedNotAvailable() = runTest {
        val status = checker(
            latestBody = """{"tag_name":"v9.9.9-../../../../Library/LaunchAgents","html_url":"https://ex.com/x"}""",
        ).check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun tagNameWithABackslashIsFailedNotAvailable() = runTest {
        val status = checker(
            latestBody = """{"tag_name":"v9.9.9-..\\..\\evil","html_url":"https://ex.com/x"}""",
        ).check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun v1PreReleaseCurrentIsTreatedAsStableAndOfferedStable() = runTest {
        // A 1.x pre-release build (isBelowStable == false) uses releases/latest and is offered the
        // next stable, matching the "pre-releases only below 1.0.0" policy.
        val status = checker(
            currentVersion = "1.0.1-beta.2",
            latestBody = """{"tag_name":"v1.0.1","html_url":"https://ex.com/1.0.1"}""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("1.0.1", status.version)
    }

    // --- Pre-stable build (0.x) → releases list ---

    @Test
    fun preStableCurrentQueriesListEndpoint() = runTest {
        val history = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request ->
            history.add(request)
            respond("[]")
        }) { expectSuccess = false }
        UpdateChecker(client, currentVersion = "0.0.9", repoSlug = "owner/repo").check()
        assertEquals(1, history.size)
        assertEquals("/repos/owner/repo/releases", history[0].url.encodedPath)
        assertEquals("100", history[0].url.parameters["per_page"])
    }

    @Test
    fun preReleaseBelowStableIsAvailableWhileCurrentIsPreStable() = runTest {
        // Both this build (0.0.9) and the release (0.1.0) are below 1.0.0 → the pre-release is offered.
        val status = checker(
            currentVersion = "0.0.9",
            listBody = """[{"tag_name":"v0.1.0","html_url":"https://ex.com/0.1.0","prerelease":true}]""",
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
            listBody = """[{"tag_name":"v0.1.0-beta","html_url":"https://ex.com/0.1.0-beta","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.1.0-beta", status.version)
    }

    @Test
    fun highestSuffixedPreReleaseIsSelected() = runTest {
        // Among eligible suffixed prereleases with equal cores, the highest identifier wins.
        val status = checker(
            currentVersion = "0.0.9",
            listBody = """[
                {"tag_name":"v0.1.0-alpha","html_url":"https://ex.com/0.1.0-alpha","prerelease":true},
                {"tag_name":"v0.1.0-beta","html_url":"https://ex.com/0.1.0-beta","prerelease":true}
            ]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.1.0-beta", status.version)
    }

    @Test
    fun preReleaseAtOrAboveStableIsIgnored() = runTest {
        // A pre-release whose version is >= 1.0.0 is never eligible, regardless of current version.
        val status = checker(
            currentVersion = "0.9.0",
            listBody = """[{"tag_name":"v1.0.0-rc1","html_url":"https://ex.com/1.0.0-rc1","prerelease":true}]""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun rejectedPreReleaseDoesNotHideEligibleStable() = runTest {
        // Newest-first: a 1.1.0 pre-release (>= 1.0.0, rejected) precedes the 1.0.0 stable → 1.0.0 wins.
        val status = checker(
            currentVersion = "0.9.0",
            listBody = """[
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
            currentVersion = "0.0.1",
            listBody = """[{"tag_name":"v0.2.0","html_url":"https://ex.com/0.2.0","draft":true}]""",
        ).check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun emptyListIsUpToDate() = runTest {
        val status = checker(currentVersion = "0.0.1", listBody = "[]").check()
        assertIs<UpdateStatus.UpToDate>(status)
    }

    @Test
    fun listServerErrorIsFailed() = runTest {
        val status = checker(currentVersion = "0.0.1", listStatus = HttpStatusCode.InternalServerError).check()
        assertIs<UpdateStatus.Failed>(status)
    }

    @Test
    fun malformedJsonIsFailed() = runTest {
        val status = checker(currentVersion = "0.0.1", listBody = "not json").check()
        assertIs<UpdateStatus.Failed>(status)
    }

    // --- Regression: a malformed tag must never mask a valid release (total-order comparator) ---

    @Test
    fun malformedTagDoesNotHideValidReleaseListedFirst() = runTest {
        // `broken` is unparseable and appears before a valid release; it must not stay selected and
        // report UpToDate. Uses a pre-stable current so selection runs over the list path.
        val status = checker(
            currentVersion = "0.0.9",
            listBody = """[
                {"tag_name":"broken","html_url":"https://ex.com/broken"},
                {"tag_name":"v0.5.0","html_url":"https://ex.com/0.5.0"}
            ]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.5.0", status.version)
    }

    @Test
    fun malformedTagDoesNotHideValidReleaseListedLast() = runTest {
        // Same as above with reversed order → selection is order-independent.
        val status = checker(
            currentVersion = "0.0.9",
            listBody = """[
                {"tag_name":"v0.5.0","html_url":"https://ex.com/0.5.0"},
                {"tag_name":"broken","html_url":"https://ex.com/broken"}
            ]""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("0.5.0", status.version)
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

    // --- Release notes / asset selection (assets[] and body parsing) ---

    @Test
    fun releaseNotesIsParsedFromBody() = runTest {
        val status = checker(
            latestBody = """{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3","body":"- swipe navigation\n- lower search minimum"}""",
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("- swipe navigation\n- lower search minimum", status.releaseNotes)
    }

    @Test
    fun releaseNotesIsNullWhenBodyIsMissing() = runTest {
        val status = checker(latestBody = """{"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3"}""").check()
        assertIs<UpdateStatus.Available>(status)
        assertNull(status.releaseNotes)
    }

    @Test
    fun assetIsSelectedForTheCurrentInstallLocation() = runTest {
        val sha256 = "a".repeat(64)
        val status = checker(
            latestBody = """
                {"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3","assets":[
                    {"name":"Keryx-1.2.3-macos-arm64.zip","browser_download_url":"https://dl/mac.zip",
                     "size":12345,"digest":"sha256:$sha256","state":"uploaded"},
                    {"name":"Keryx-1.2.3-windows-x86_64.msi","browser_download_url":"https://dl/win.msi",
                     "size":6789,"digest":"sha256:$sha256","state":"uploaded"}
                ]}
            """.trimIndent(),
            location = fakeMacLocation,
        ).check()
        assertIs<UpdateStatus.Available>(status)
        val asset = status.asset
        assertEquals("Keryx-1.2.3-macos-arm64.zip", asset?.name)
        assertEquals("https://dl/mac.zip", asset?.downloadUrl)
        assertEquals(12345L, asset?.sizeBytes)
        assertEquals(sha256, asset?.sha256)
        assertEquals(UpdateAssetKind.MAC_APP_ZIP, asset?.kind)
    }

    @Test
    fun assetIsNullWhenInstallLocationHasNoUpdatePath() = runTest {
        val sha256 = "a".repeat(64)
        val status = checker(
            latestBody = """
                {"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3","assets":[
                    {"name":"Keryx-1.2.3-macos-arm64.zip","browser_download_url":"https://dl/mac.zip",
                     "size":12345,"digest":"sha256:$sha256","state":"uploaded"}
                ]}
            """.trimIndent(),
            location = fakeUnsupportedLocation,
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertNull(status.asset)
    }

    @Test
    fun assetStillBeingProcessedByGitHubIsNotSelected() = runTest {
        val sha256 = "a".repeat(64)
        val status = checker(
            latestBody = """
                {"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3","assets":[
                    {"name":"Keryx-1.2.3-macos-arm64.zip","browser_download_url":"https://dl/mac.zip",
                     "size":12345,"digest":"sha256:$sha256","state":"open"}
                ]}
            """.trimIndent(),
            location = fakeMacLocation,
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertNull(status.asset)
    }

    @Test
    fun malformedAssetEntryIsSkippedWithoutFailingTheWholeCheck() = runTest {
        val sha256 = "a".repeat(64)
        val status = checker(
            latestBody = """
                {"tag_name":"v1.2.3","html_url":"https://ex.com/1.2.3","assets":[
                    {"browser_download_url":"https://dl/no-name.zip","size":1,"digest":"sha256:$sha256"},
                    {"name":"Keryx-1.2.3-macos-arm64.zip","browser_download_url":"https://dl/mac.zip",
                     "size":12345,"digest":"sha256:$sha256","state":"uploaded"}
                ]}
            """.trimIndent(),
            location = fakeMacLocation,
        ).check()
        assertIs<UpdateStatus.Available>(status)
        assertEquals("Keryx-1.2.3-macos-arm64.zip", status.asset?.name)
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

    @Test
    fun buildMetadataIsIgnored() {
        // SemVer §10: build metadata (`+...`) must not affect precedence.
        assertEquals(false, isNewer("1.0.1+build.7", "1.0.1"))   // metadata alone ≠ newer
        assertEquals(true, isNewer("1.0.1+build.7", "1.0.0"))    // core still decides
        assertEquals(false, isNewer("1.0.0-alpha+001", "1.0.0")) // prerelease < release, metadata ignored
        assertEquals(false, isNewer("1.0.0+a", "1.0.0+b"))       // differing metadata → equal
    }
}

class CompareReleaseVersionsTest {

    @Test
    fun equalVersionsCompareZero() {
        assertEquals(0, compareReleaseVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun higherVersionComparesPositive() {
        assertTrue(compareReleaseVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareReleaseVersions("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun unparseableRanksBelowParseable() {
        assertTrue(compareReleaseVersions("broken", "1.0.0") < 0)
        assertTrue(compareReleaseVersions("1.0.0", "broken") > 0)
    }

    @Test
    fun nullRanksBelowParseable() {
        assertTrue(compareReleaseVersions(null, "1.0.0") < 0)
        assertTrue(compareReleaseVersions("1.0.0", null) > 0)
    }

    @Test
    fun bothUnparseableOrNullCompareZero() {
        assertEquals(0, compareReleaseVersions("broken", "also-broken"))
        assertEquals(0, compareReleaseVersions(null, null))
        assertEquals(0, compareReleaseVersions(null, "broken"))
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
