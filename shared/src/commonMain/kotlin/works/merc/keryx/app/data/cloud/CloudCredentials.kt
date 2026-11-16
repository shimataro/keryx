package works.merc.keryx.app.data.cloud

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The credentials a [TokenStorage] persists for one cloud provider. Either OAuth 2.0 tokens
 * ([OAuthTokens] — Dropbox, Google Drive, OneDrive) or a long-lived app password
 * ([AppPasswordCredentials]) for a provider that does not speak OAuth.
 *
 * Serialized through [CloudCredentialsSerializer], never through the default sealed-class
 * serializer: the default one writes and requires a `"type"` discriminator, so tokens saved before
 * this type existed would no longer decode and the user would silently find themselves
 * disconnected.
 */
@Serializable(with = CloudCredentialsSerializer::class)
sealed interface CloudCredentials

/**
 * Credentials for a server reached with an app password over HTTP Basic authentication.
 *
 * @property serverUrl The server's normalized base URL.
 * @property userId The account's user id, which may differ from [loginName] (used in DAV paths).
 * @property loginName The name used to authenticate.
 * @property appPassword The app password. Never shown by [toString].
 */
@Serializable
data class AppPasswordCredentials(
    val serverUrl: String,
    val userId: String,
    val loginName: String,
    val appPassword: String,
) : CloudCredentials {
    /** Masks [appPassword] so the credentials can never leak into a log line or an exception message. */
    override fun toString(): String =
        "AppPasswordCredentials(serverUrl=$serverUrl, userId=$userId, loginName=$loginName, appPassword=***)"
}

/**
 * Picks the [CloudCredentials] subtype from the JSON object's keys rather than from a
 * discriminator: an `appPassword` key means [AppPasswordCredentials], anything else is decoded as
 * [OAuthTokens] (and fails if it is not one). Encoding delegates to the subtype's own serializer
 * and adds no discriminator, so an [OAuthTokens] is written byte for byte as it was before
 * [CloudCredentials] existed — the stored format stays readable by older builds too.
 */
object CloudCredentialsSerializer : JsonContentPolymorphicSerializer<CloudCredentials>(CloudCredentials::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CloudCredentials> =
        if (APP_PASSWORD_KEY in element.jsonObject) AppPasswordCredentials.serializer() else OAuthTokens.serializer()

    private const val APP_PASSWORD_KEY = "appPassword"
}

/**
 * Adapts a credentials supplier to the access-token supplier the OAuth storages
 * ([DropboxStorage], [GoogleDriveStorage], [OneDriveStorage]) take: the supplied [OAuthTokens]'
 * access token, or null when there are none (or they are not OAuth tokens).
 */
fun oauthAccessToken(credentialsProvider: suspend () -> CloudCredentials?): suspend () -> String? =
    { (credentialsProvider() as? OAuthTokens)?.accessToken }
