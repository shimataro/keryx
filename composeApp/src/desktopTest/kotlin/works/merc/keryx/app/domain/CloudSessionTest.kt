package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenSaveOutcome
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudSessionTest {
    private fun client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = false }

    private fun authManager(handler: MockRequestHandler): DropboxAuthManager =
        DropboxAuthManager(client(handler), clock = Clock { 1_000_000L })

    private fun session(
        tokenStorage: FakeTokenStorage,
        clientId: String = "APPKEY",
        authHandler: MockRequestHandler = { respond("{}", HttpStatusCode.OK) },
        clock: Clock = Clock { 0L },
        selectedType: () -> CloudStorageType? = { CloudStorageType.DROPBOX },
        notificationCenter: NotificationCenter = NotificationCenter(),
    ) = singleProviderCloudSession(
        client = client(authHandler),
        tokenStorage = tokenStorage,
        authManager = authManager(authHandler),
        clientId = clientId,
        clock = clock,
        selectedType = selectedType,
        notificationCenter = notificationCenter,
    )

    @Test
    fun isConnectedFalseWhenClientIdEmpty() {
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "")
        assertTrue(!s.isConnected())
    }

    @Test
    fun isConnectedFalseWhenNoTokensStored() {
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "APPKEY")
        assertTrue(!s.isConnected())
    }

    @Test
    fun isConnectedFalseWhenNothingSelected() {
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "APPKEY", selectedType = { null })
        assertTrue(!s.isConnected())
        assertNull(s.connectedType())
        assertNull(s.current())
    }

    @Test
    fun isConnectedTrueWhenSelectedConfiguredAndTokensPresent() {
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "APPKEY")
        assertTrue(s.isConnected())
        assertEquals(CloudStorageType.DROPBOX, s.connectedType())
    }

    @Test
    fun connectFlowReturnsNullWhenClientIdEmpty() {
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "")
        assertNull(s.connectFlow(CloudStorageType.DROPBOX))
    }

    @Test
    fun connectFlowReturnedWhenConfigured() {
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "APPKEY")
        assertNotNull(s.connectFlow(CloudStorageType.DROPBOX))
    }

    @Test
    fun currentReturnsNullWhenClientIdEmpty() {
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "")
        assertNull(s.current())
    }

    @Test
    fun currentReturnsNullWhenNoTokensStored() {
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "APPKEY")
        assertNull(s.current())
    }

    @Test
    fun currentReturnsCloudStorageWhenConnected() {
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "APPKEY")
        assertNotNull(s.current())
    }

    @Test
    fun saveTokensPersistsToTokenStorage() = runBlocking {
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "APPKEY")

        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        assertEquals("AT", storage.stored?.accessToken)
        assertEquals("RT", storage.stored?.refreshToken)
    }

    @Test
    fun saveTokensRaisesNoNotificationWhenStoredSecurely() = runBlocking {
        val center = NotificationCenter()
        val s = session(FakeTokenStorage(null, outcome = TokenSaveOutcome.SECURE), notificationCenter = center)

        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        assertEquals(emptyList(), center.items.value)
    }

    @Test
    fun saveTokensWarnsWhenTheTokenOnlyReachedThePlaintextFallback() = runBlocking {
        val center = NotificationCenter()
        val s = session(FakeTokenStorage(null, outcome = TokenSaveOutcome.PLAINTEXT_FILE), notificationCenter = center)

        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        val notification = center.items.value.single()
        assertEquals(AppNotificationLevel.WARNING, notification.level)
        assertEquals("tokenStorageFallback", notification.message)
        assertEquals(
            AppNotificationAction.ShowInfoDialog("tokenStorageFallbackDetail"),
            notification.action,
        )
    }

    /**
     * "Saved in a plaintext file" and "not saved anywhere" need different messages: only the
     * second one means the tokens vanish on exit and the account has to be connected again. A
     * shared message would tell the user their sign-in is in a file that was never written.
     */
    @Test
    fun saveTokensWarnsDistinctlyWhenTheTokenCouldNotBePersistedAtAll() = runBlocking {
        val center = NotificationCenter()
        val s = session(FakeTokenStorage(null, outcome = TokenSaveOutcome.NOT_PERSISTED), notificationCenter = center)

        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        val notification = center.items.value.single()
        assertEquals(AppNotificationLevel.WARNING, notification.level)
        assertEquals("tokenStorageNotPersisted", notification.message)
        assertEquals(
            AppNotificationAction.ShowInfoDialog("tokenStorageNotPersistedDetail"),
            notification.action,
        )
    }

    /**
     * A connect followed by a refresh that also falls back must leave one bell entry, not two:
     * the refresh path recurs on every expiry, so the warning has to coalesce.
     */
    @Test
    fun repeatedFallbackSavesCoalesceIntoASingleNotification() = runBlocking {
        val center = NotificationCenter()
        val storage = FakeTokenStorage(null, outcome = TokenSaveOutcome.PLAINTEXT_FILE)
        val refreshBody = """{"access_token":"FRESH","expires_in":14400}"""
        val s = session(
            storage,
            authHandler = { request ->
                if (request.url.encodedPath.contains("oauth2/token")) {
                    respond(refreshBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L },
            notificationCenter = center,
        )

        // 1st fallback save: the initial connect.
        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L))
        // 2nd fallback save: the expired token is refreshed and persisted again.
        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        cloudStorage.authenticate()

        assertEquals("FRESH", storage.stored?.accessToken) // the refresh really did save again
        assertEquals(1, center.items.value.size)
        assertEquals(AppNotificationLevel.WARNING, center.items.value.single().level)
    }

    @Test
    fun disconnectRevokesTokenThenClearsStorage() = runBlocking {
        var authHeader: String? = null
        val storage = FakeTokenStorage(OAuthTokens("AT"))
        val s = session(storage, clientId = "APPKEY", authHandler = { request ->
            authHeader = request.headers["Authorization"]
            respond("", HttpStatusCode.OK)
        })

        s.disconnect(CloudStorageType.DROPBOX)

        assertEquals("Bearer AT", authHeader)
        assertNull(storage.stored)
    }

    @Test
    fun disconnectWithNoStoredTokenSkipsRevokeAndClears() = runBlocking {
        var revokeCalled = false
        val storage = FakeTokenStorage(null)
        val s = session(storage, clientId = "APPKEY", authHandler = {
            revokeCalled = true
            respond("", HttpStatusCode.OK)
        })

        s.disconnect(CloudStorageType.DROPBOX)

        assertTrue(!revokeCalled)
        assertNull(storage.stored)
    }

    @Test
    fun accessTokenNotExpiredReturnsRawTokenWithoutNetworkCall() = runBlocking {
        var callCount = 0
        val storage = FakeTokenStorage(
            OAuthTokens("AT", "RT", expiresAtMillis = 1_000_000L),
        )
        val s = session(
            storage,
            clientId = "APPKEY",
            authHandler = {
                callCount++
                respond("{}", HttpStatusCode.OK)
            },
            clock = Clock { 0L }, // well before expiry
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        val result = cloudStorage.authenticate()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, callCount) // only the get_current_account call, no refresh
    }

    @Test
    fun accessTokenExpiredWithRefreshTokenRefreshesAndPersists() = runBlocking {
        val storage = FakeTokenStorage(
            OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L),
        )
        val refreshBody = """{"access_token":"FRESH","expires_in":14400}"""
        val s = session(
            storage,
            clientId = "APPKEY",
            authHandler = { request ->
                if (request.url.encodedPath.contains("oauth2/token")) {
                    respond(refreshBody, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L }, // well past expiry
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        val result = cloudStorage.authenticate()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals("FRESH", storage.stored?.accessToken)
        assertEquals("RT", storage.stored?.refreshToken)
    }

    @Test
    fun accessTokenExpiredWithNoRefreshTokenFallsBackToStaleToken() = runBlocking {
        var authHeaderSeen: String? = null
        val storage = FakeTokenStorage(
            OAuthTokens("STALE", refreshToken = null, expiresAtMillis = 1_000L),
        )
        val s = session(
            storage,
            clientId = "APPKEY",
            authHandler = { request ->
                authHeaderSeen = request.headers["Authorization"]
                respond("{}", HttpStatusCode.OK)
            },
            clock = Clock { 2_000_000L },
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        cloudStorage.authenticate()

        assertEquals("Bearer STALE", authHeaderSeen)
    }

    @Test
    fun accessTokenRefreshFailureReturnsCloudAuthException(): Unit = runBlocking {
        val storage = FakeTokenStorage(
            OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L),
        )
        val s = session(
            storage,
            clientId = "APPKEY",
            authHandler = { request ->
                if (request.url.encodedPath.contains("oauth2/token")) {
                    respond("", HttpStatusCode.BadRequest)
                } else {
                    respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L },
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        val result = cloudStorage.authenticate()

        // Null access token means withToken() short-circuits with CloudAuthException.
        assertIs<Result.Err>(result)
    }
}
