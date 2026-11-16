package works.merc.keryx.app.data.cloud

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [CloudCredentialsSerializer]'s compatibility contract: tokens stored before
 * [CloudCredentials] existed still decode, and an [OAuthTokens] is still written in exactly that
 * shape (no class discriminator), so an upgrade never silently disconnects a provider.
 */
class CloudCredentialsSerializerTest {
    /** The same configuration every production [TokenStorage] uses. */
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(text: String): CloudCredentials = json.decodeFromString(CloudCredentialsSerializer, text)

    private fun encode(credentials: CloudCredentials): String = json.encodeToString(CloudCredentialsSerializer, credentials)

    @Test
    fun encodingOAuthTokensAddsNoDiscriminator() {
        assertEquals(
            """{"accessToken":"AT","refreshToken":"RT"}""",
            encode(OAuthTokens(accessToken = "AT", refreshToken = "RT")),
        )
    }

    @Test
    fun encodingOAuthTokensMatchesTheirOwnSerializerForEveryFieldCombination() {
        listOf(
            OAuthTokens(accessToken = "AT"),
            OAuthTokens(accessToken = "AT", refreshToken = "RT"),
            OAuthTokens(accessToken = "AT", expiresAtMillis = 1_700_000_000_000L),
            OAuthTokens(accessToken = "AT", refreshToken = "RT", expiresAtMillis = 1_700_000_000_000L),
        ).forEach { tokens ->
            // The legacy format is exactly what OAuthTokens' own serializer produced.
            assertEquals(json.encodeToString(OAuthTokens.serializer(), tokens), encode(tokens))
        }
    }

    @Test
    fun legacyJsonWithOnlyAnAccessTokenDecodes() {
        assertEquals(OAuthTokens(accessToken = "AT"), decode("""{"accessToken":"AT"}"""))
    }

    @Test
    fun legacyJsonWithARefreshTokenDecodes() {
        assertEquals(
            OAuthTokens(accessToken = "AT", refreshToken = "RT"),
            decode("""{"accessToken":"AT","refreshToken":"RT"}"""),
        )
    }

    @Test
    fun legacyJsonWithAnExpiryDecodes() {
        assertEquals(
            OAuthTokens(accessToken = "AT", expiresAtMillis = 1_700_000_000_000L),
            decode("""{"accessToken":"AT","expiresAtMillis":1700000000000}"""),
        )
    }

    @Test
    fun legacyJsonWithARefreshTokenAndAnExpiryDecodes() {
        assertEquals(
            OAuthTokens(accessToken = "AT", refreshToken = "RT", expiresAtMillis = 1_700_000_000_000L),
            decode("""{"accessToken":"AT","refreshToken":"RT","expiresAtMillis":1700000000000}"""),
        )
    }

    @Test
    fun legacyJsonWithExplicitNullsDecodes() {
        assertEquals(
            OAuthTokens(accessToken = "AT"),
            decode("""{"accessToken":"AT","refreshToken":null,"expiresAtMillis":null}"""),
        )
    }

    @Test
    fun appPasswordCredentialsRoundTrip() {
        val credentials = AppPasswordCredentials(
            serverUrl = "https://cloud.example.com",
            userId = "alice",
            loginName = "alice@example.com",
            appPassword = "secret-app-password",
        )

        val decoded = decode(encode(credentials))

        assertIs<AppPasswordCredentials>(decoded)
        assertEquals(credentials, decoded)
    }

    @Test
    fun appPasswordCredentialsEncodeWithoutADiscriminator() {
        val encoded = encode(AppPasswordCredentials("https://cloud.example.com", "alice", "alice", "pw"))

        assertEquals(
            """{"serverUrl":"https://cloud.example.com","userId":"alice","loginName":"alice","appPassword":"pw"}""",
            encoded,
        )
    }

    @Test
    fun jsonMatchingNeitherShapeFailsToDecode() {
        assertFailsWith<SerializationException> { decode("""{"foo":"bar"}""") }
    }

    @Test
    fun anAppPasswordWithoutTheRestOfItsFieldsFailsToDecode() {
        assertFailsWith<SerializationException> { decode("""{"appPassword":"pw","accessToken":"AT"}""") }
    }

    @Test
    fun appPasswordCredentialsToStringMasksThePassword() {
        val text = AppPasswordCredentials(
            serverUrl = "https://cloud.example.com",
            userId = "alice",
            loginName = "alice",
            appPassword = "secret-app-password",
        ).toString()

        assertFalse("secret-app-password" in text)
        assertTrue("https://cloud.example.com" in text)
    }
}
