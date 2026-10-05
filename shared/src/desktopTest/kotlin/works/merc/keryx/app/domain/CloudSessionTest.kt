package works.merc.keryx.app.domain

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
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
        accessTokenProvider: (suspend () -> String?)? = null,
    ) = singleProviderCloudSession(
        client = client(authHandler),
        tokenStorage = tokenStorage,
        authManager = authManager(authHandler),
        clientId = clientId,
        clock = clock,
        accessTokenProvider = accessTokenProvider,
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
        assertEquals(NotificationText.TokenStorageFallback, notification.text)
        assertEquals(
            AppNotificationAction.ShowInfoDialog(InfoDialogText.TOKEN_STORAGE_FALLBACK),
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
        assertEquals(NotificationText.TokenStorageNotPersisted, notification.text)
        assertEquals(
            AppNotificationAction.ShowInfoDialog(InfoDialogText.TOKEN_STORAGE_NOT_PERSISTED),
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

    /**
     * A save that reached no store must still leave this session connected until the app exits:
     * the connect flow already reports success, so a session that then read "no tokens" back from
     * the store would skip every sync silently.
     */
    @Test
    fun unpersistedTokensKeepTheSessionConnectedForThisProcess() = runBlocking {
        var authHeaderSeen: String? = null
        val storage = FakeTokenStorage(null, outcome = TokenSaveOutcome.NOT_PERSISTED)
        val s = session(storage, authHandler = { request ->
            authHeaderSeen = request.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        })

        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        assertNull(storage.stored)
        assertTrue(s.isConnected())
        assertEquals(CloudStorageType.DROPBOX, s.connectedType())
        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        cloudStorage.authenticate()
        assertEquals("Bearer AT", authHeaderSeen)
    }

    /**
     * A refresh whose save fails leaves the previous tokens in the store; the refreshed ones held
     * in memory must win, or the next call would hand out the stale (possibly rotated-out) token.
     */
    @Test
    fun anUnpersistedRefreshTakesPrecedenceOverTheStaleStoredTokens() = runBlocking {
        val authHeaders = mutableListOf<String?>()
        var refreshCalls = 0
        val storage = FakeTokenStorage(OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L))
        val s = session(
            storage,
            authHandler = { request ->
                if (request.url.encodedPath.contains("oauth2/token")) {
                    refreshCalls++
                    respond("""{"access_token":"FRESH","expires_in":14400}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                } else {
                    authHeaders += request.headers["Authorization"]
                    respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L },
        )
        storage.outcome = TokenSaveOutcome.NOT_PERSISTED

        assertNotNull(s.current()).authenticate()
        assertNotNull(s.current()).authenticate()

        assertEquals("STALE", storage.stored?.accessToken) // the refresh never reached the store
        assertEquals(listOf<String?>("Bearer FRESH", "Bearer FRESH"), authHeaders)
        assertEquals(1, refreshCalls) // the second call used the in-memory token, no second refresh
    }

    /** Once a save reaches a store again, that store is authoritative and the memory copy is dropped. */
    @Test
    fun aLaterPersistedSaveReplacesTheInMemoryTokens() = runBlocking {
        var authHeaderSeen: String? = null
        val storage = FakeTokenStorage(null, outcome = TokenSaveOutcome.NOT_PERSISTED)
        val s = session(storage, authHandler = { request ->
            authHeaderSeen = request.headers["Authorization"]
            respond("{}", HttpStatusCode.OK)
        })
        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("MEMORY", "RT"))

        storage.outcome = TokenSaveOutcome.SECURE
        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("STORED", "RT"))

        assertNotNull(s.current()).authenticate()
        assertEquals("Bearer STORED", authHeaderSeen)
    }

    @Test
    fun disconnectRevokesAndForgetsUnpersistedTokens() = runBlocking {
        var authHeaderSeen: String? = null
        val storage = FakeTokenStorage(null, outcome = TokenSaveOutcome.NOT_PERSISTED)
        val s = session(storage, authHandler = { request ->
            authHeaderSeen = request.headers["Authorization"]
            respond("", HttpStatusCode.OK)
        })
        s.saveTokens(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        s.disconnect(CloudStorageType.DROPBOX)

        assertEquals("Bearer AT", authHeaderSeen)
        assertTrue(!s.isConnected())
        assertNull(s.current())
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

    /**
     * A disconnect landing while a refresh is suspended on the network must not be undone by that
     * refresh saving its new tokens afterwards. Returns the Authorization header the revoke was sent
     * with, so a caller can check the refreshed token — not the stale one — was revoked.
     */
    private suspend fun disconnectDuringRefresh(storage: FakeTokenStorage): Pair<CloudSession, String?> = coroutineScope {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        var revokeAuthHeader: String? = null
        val s = session(
            storage,
            authHandler = { request ->
                val path = request.url.encodedPath
                when {
                    path.contains("oauth2/token") -> {
                        refreshStarted.complete(Unit)
                        releaseRefresh.await()
                        respond("""{"access_token":"FRESH","expires_in":14400}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                    path.contains("token/revoke") -> {
                        revokeAuthHeader = request.headers["Authorization"]
                        respond("", HttpStatusCode.OK)
                    }
                    else -> respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L }, // well past expiry
        )
        val cloudStorage = assertNotNull(s.current())

        val sync = launch { cloudStorage.authenticate() }
        refreshStarted.await()
        val disconnect = launch { s.disconnect(CloudStorageType.DROPBOX) }
        // Unserialized, the disconnect finishes here, before the refresh resumes; serialized, it waits.
        withTimeoutOrNull(500) { disconnect.join() }
        releaseRefresh.complete(Unit)
        sync.join()
        disconnect.join()
        s to revokeAuthHeader
    }

    @Test
    fun aRefreshInFlightCannotRestoreTokensAfterDisconnect() = runBlocking {
        val storage = FakeTokenStorage(OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L))

        val (s, revokeAuthHeader) = disconnectDuringRefresh(storage)

        assertNull(storage.stored)
        assertTrue(!s.isConnected())
        assertEquals("Bearer FRESH", revokeAuthHeader)
    }

    @Test
    fun anUnpersistedRefreshInFlightCannotSurviveDisconnect() = runBlocking {
        val storage = FakeTokenStorage(
            OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L),
            outcome = TokenSaveOutcome.NOT_PERSISTED,
        )

        val (s, revokeAuthHeader) = disconnectDuringRefresh(storage)

        assertNull(storage.stored)
        assertTrue(!s.isConnected())
        assertEquals("Bearer FRESH", revokeAuthHeader)
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

    /**
     * A provider that supplies its own access token (Android's Google Drive, where Play services
     * owns the grant) must bypass the stored-token + refresh path entirely — otherwise the branch
     * exercised by [accessTokenExpiredWithNoRefreshTokenFallsBackToStaleToken] would hand out a
     * token that expired an hour ago.
     */
    @Test
    fun accessTokenProviderOverridesTheStoredTokenAndRefreshPath() = runBlocking {
        var authHeaderSeen: String? = null
        var refreshCalls = 0
        val storage = FakeTokenStorage(
            OAuthTokens("STALE", "RT", expiresAtMillis = 1_000L),
        )
        val s = session(
            storage,
            clientId = "APPKEY",
            authHandler = { request ->
                if (request.url.encodedPath.contains("oauth2/token")) {
                    refreshCalls++
                    respond("""{"access_token":"REFRESHED"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                } else {
                    authHeaderSeen = request.headers["Authorization"]
                    respond("{}", HttpStatusCode.OK)
                }
            },
            clock = Clock { 2_000_000L }, // well past the stored token's expiry
            accessTokenProvider = { "FRESH" },
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)
        cloudStorage.authenticate()

        assertEquals("Bearer FRESH", authHeaderSeen)
        assertEquals(0, refreshCalls)
        // Nothing was re-persisted: the override's token lives only for the call it was fetched for.
        assertEquals("STALE", storage.stored?.accessToken)
    }

    /**
     * The override's "the user has to consent again" answer. It has to reach the caller as an
     * ordinary auth failure — that is what turns a background sync with a withdrawn grant into a
     * notification-center entry rather than a silent no-op.
     */
    @Test
    fun accessTokenProviderReturningNullSurfacesAsAuthError(): Unit = runBlocking {
        val storage = FakeTokenStorage(OAuthTokens("AT", "RT", expiresAtMillis = 1_000_000L))
        val s = session(
            storage,
            clientId = "APPKEY",
            clock = Clock { 0L }, // the stored token is still valid, so only the override can fail this
            accessTokenProvider = { null },
        )

        val cloudStorage = s.current()
        assertNotNull(cloudStorage)

        assertIs<Result.Err>(cloudStorage.authenticate())
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
