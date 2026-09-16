package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.GOOGLE_DRIVE_APPDATA_SCOPE
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers the PR #210 review finding that [tokenFrom] used to accept any non-null access token
 * without checking the granted scopes. Play services can answer a partially-granted consent that
 * way, which would have let the connect report success and only fail later as a 403 on every Drive
 * request.
 *
 * Lives in `androidDeviceTest` because [tokenFrom] is `androidMain` code (there is no
 * `androidUnitTest` source set — see docs/testing.md), but it deliberately touches neither Play
 * services nor an `AuthorizationResult`, so it needs nothing of the device beyond the JVM.
 */
class PlayServicesGoogleDriveAuthDeviceTest {

    @Test
    fun acceptsATokenGrantedTheAppDataScope() {
        val result = tokenFrom("ya29.token", listOf(GOOGLE_DRIVE_APPDATA_SCOPE))

        assertEquals(Result.Ok("ya29.token"), result)
    }

    @Test
    fun rejectsATokenWhoseConsentOmittedTheAppDataScope() {
        val result = tokenFrom("ya29.token", listOf("https://www.googleapis.com/auth/userinfo.email"))

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }

    @Test
    fun rejectsAResultThatGrantedNoScopesAtAll() {
        val result = tokenFrom("ya29.token", emptyList())

        assertIs<Result.Err>(result)
    }

    @Test
    fun rejectsAGrantedScopeWithNoAccessToken() {
        val result = tokenFrom(null, listOf(GOOGLE_DRIVE_APPDATA_SCOPE))

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
    }
}
