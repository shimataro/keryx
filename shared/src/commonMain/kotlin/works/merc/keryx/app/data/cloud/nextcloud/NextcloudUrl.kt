package works.merc.keryx.app.data.cloud.nextcloud

import works.merc.keryx.app.core.NextcloudServerException
import works.merc.keryx.app.core.NextcloudServerProblem
import works.merc.keryx.app.core.Result

private const val HTTPS_PREFIX = "https://"

/** A URI scheme followed by `://` at the start of the input. */
private val SCHEME_PATTERN = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

/** A DNS name / IPv4 address, or a bracketed IPv6 literal, with an optional port. */
private val AUTHORITY_PATTERN = Regex("^(?:[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*|\\[[0-9A-Fa-f:.]+])(?::[0-9]{1,5})?$")

/**
 * Path segments where a URL copied from the browser stops being the server's base: everything from
 * the first of them on is dropped (`https://host/nextcloud/index.php/apps/files` → `https://host/nextcloud`).
 */
private val ENTRY_POINT_SEGMENTS = listOf("/index.php", "/remote.php", "/status.php", "/ocs/")

/** Trailing pages a pasted base URL may still end in, dropped after [ENTRY_POINT_SEGMENTS]. */
private val TRAILING_PAGES = listOf("/login")

/**
 * Turns what the user typed for a Nextcloud server into its normalized base URL —
 * `https://host[:port][/subdirectory]`, no trailing slash — or rejects it.
 *
 * - Surrounding whitespace is ignored, and an address without a scheme gets `https://`.
 * - Any scheme other than `https` (plain `http://` included) is rejected as
 *   [NextcloudServerProblem.HTTPS_REQUIRED]: the app password travels on every request, so it is
 *   never sent unencrypted.
 * - A URL copied from the browser is cut back to the server's base: everything from `/index.php`
 *   (or `/remote.php`, `/status.php`, `/ocs/`) on, a trailing `/login`, and trailing slashes go.
 * - Embedded credentials (`user@host`), a query or a fragment, and anything that is not a plain host
 *   name, IP address or bracketed IPv6 literal (with an optional port) are rejected as
 *   [NextcloudServerProblem.NOT_NEXTCLOUD].
 *
 * Pure: it makes no request. Whether a Nextcloud server actually answers there is
 * [NextcloudServerProbe]'s job.
 *
 * @param input The address as typed.
 * @return The normalized base URL, or a [NextcloudServerException] saying why it was rejected.
 */
fun normalizeNextcloudUrl(input: String): Result<String> {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return notNextcloud("Empty server address")
    val scheme = SCHEME_PATTERN.find(trimmed)?.groupValues?.get(1)
    if (scheme != null && !scheme.equals("https", ignoreCase = true)) {
        return Result.Err(NextcloudServerException(NextcloudServerProblem.HTTPS_REQUIRED, "Server address must use HTTPS"))
    }
    val rest = if (scheme != null) trimmed.substring(scheme.length + "://".length) else trimmed
    if (rest.any { it == '?' || it == '#' || it.isWhitespace() }) {
        return notNextcloud("Server address must not contain a query, fragment or whitespace")
    }
    val authority = rest.substringBefore('/')
    if ('@' in authority) return notNextcloud("Server address must not contain credentials")
    if (!AUTHORITY_PATTERN.matches(authority)) return notNextcloud("Malformed server host")
    val path = basePath(rest.substring(authority.length))
    return Result.Ok(HTTPS_PREFIX + authority.lowercase() + path)
}

/** [rawPath] cut back to the server's base path, without a trailing slash (`""` for the root). */
private fun basePath(rawPath: String): String {
    var path = rawPath
    ENTRY_POINT_SEGMENTS
        .mapNotNull { segment -> path.indexOf(segment).takeIf { it >= 0 } }
        .minOrNull()
        ?.let { path = path.substring(0, it) }
    path = path.trimEnd('/')
    TRAILING_PAGES.firstOrNull { path.endsWith(it) }?.let { path = path.removeSuffix(it) }
    return path.trimEnd('/')
}

private fun notNextcloud(message: String): Result.Err =
    Result.Err(NextcloudServerException(NextcloudServerProblem.NOT_NEXTCLOUD, message))

/** Whether [url] is an `https://` URL — the guard every Nextcloud request URL passes first. */
internal fun isHttpsUrl(url: String): Boolean = url.startsWith(HTTPS_PREFIX, ignoreCase = true)
