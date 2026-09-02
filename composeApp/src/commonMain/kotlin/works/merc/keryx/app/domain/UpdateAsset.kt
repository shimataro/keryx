package works.merc.keryx.app.domain

import works.merc.keryx.app.core.APP_NAME
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation

/**
 * One entry of a GitHub release's `assets[]` array, as returned by the Releases API. `digest` and
 * `state` were added to that API after some already-published releases, so both are nullable —
 * [selectUpdateAsset] treats a missing digest the same as an unverifiable one (never selected).
 */
internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val digest: String?,
    val state: String?,
)

/** Which release asset an in-app update would install, driving both the download and the
 * installer that eventually consumes it. */
enum class UpdateAssetKind { MAC_APP_ZIP, WINDOWS_MSI, WINDOWS_ZIP, LINUX_ZIP, ANDROID_APK }

/** A release asset [selectUpdateAsset] picked out as installable here, with its integrity digest
 * already parsed into the form [works.merc.keryx.app.data.remote.UpdateDownloader] checks against. */
data class UpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: UpdateAssetKind,
)

/**
 * A generous sanity ceiling on a release asset's declared size, checked before anything downstream
 * (the free-space guard, the download itself) ever trusts it. Real update assets top out at a few
 * hundred MB; this exists only to reject a wildly implausible value — a compromised or malformed
 * release response — before it can push [hasEnoughFreeSpaceForUpdate]'s arithmetic anywhere near an
 * overflow, not to police legitimate release growth.
 */
private const val MAX_PLAUSIBLE_UPDATE_ASSET_SIZE_BYTES = 1024L * 1024 * 1024 // 1 GiB

/** The asset name suffix each [UpdateAssetKind] is matched by, e.g. `Keryx-0.14.0-macos-arm64.zip`
 * ([APP_NAME] is `"Keryx"`). */
private val ASSET_SUFFIX_BY_KIND = mapOf(
    UpdateAssetKind.MAC_APP_ZIP to "-macos-arm64.zip",
    UpdateAssetKind.WINDOWS_MSI to "-windows-x86_64.msi",
    UpdateAssetKind.WINDOWS_ZIP to "-windows-x86_64.zip",
    UpdateAssetKind.LINUX_ZIP to "-linux-x86_64.zip",
    UpdateAssetKind.ANDROID_APK to "-android-universal.apk",
)

/**
 * Full-match pattern each [UpdateAssetKind]'s asset name must satisfy — not just the prefix/suffix
 * [selectUpdateAsset] used to check alone. [UpdateAsset.name] ends up as a path component
 * ([works.merc.keryx.app.domain.UpdateRepository]'s `updateDownloadDir`/`destPath`), so this rejects
 * anything a path shouldn't see (`/`, `\`, `..`, shell metacharacters) rather than sanitizing it —
 * a release whose asset name doesn't match this exactly is treated the same as one with no matching
 * asset at all: [selectUpdateAsset] returns `null`, and no in-app update is offered.
 */
private val ASSET_NAME_PATTERN_BY_KIND: Map<UpdateAssetKind, Regex> =
    ASSET_SUFFIX_BY_KIND.mapValues { (_, suffix) ->
        Regex("^${Regex.escape(APP_NAME)}-[A-Za-z0-9._+-]+${Regex.escape(suffix)}$")
    }

/** The [UpdateAssetKind] this [InstallLocation] would need, or `null` when no in-app update path
 * applies to this install form at all (regardless of what the release actually shipped). */
private fun assetKindFor(location: InstallLocation): UpdateAssetKind? = when (location.kind) {
    InstallKind.MAC_APP_BUNDLE -> UpdateAssetKind.MAC_APP_ZIP
    InstallKind.WINDOWS_INSTALLED -> UpdateAssetKind.WINDOWS_MSI
    InstallKind.WINDOWS_PORTABLE -> UpdateAssetKind.WINDOWS_ZIP
    InstallKind.LINUX_PORTABLE -> UpdateAssetKind.LINUX_ZIP
    InstallKind.ANDROID_SIDELOADED -> UpdateAssetKind.ANDROID_APK
    InstallKind.LINUX_PACKAGE,
    InstallKind.ANDROID_STORE,
    InstallKind.DEVELOPMENT,
    InstallKind.UNKNOWN,
    -> null
}

/**
 * Extracts the lower-case hex SHA-256 from a GitHub Releases API digest string (`"sha256:<hex>"`).
 * Returns `null` for any other algorithm, a malformed value, or `null` input — never guesses.
 */
internal fun parseSha256Digest(digest: String?): String? {
    if (digest == null) return null
    val parts = digest.split(":", limit = 2)
    if (parts.size != 2 || parts[0] != "sha256") return null
    val hex = parts[1]
    return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * Picks the one release asset appropriate for [location], or `null` when this build/install form
 * has no in-app update path here — no matching [InstallKind] ([assetKindFor]), no asset of that
 * kind in this release (e.g. a prerelease that omits the `.msi`), an upload GitHub hasn't finished
 * processing yet (`state` present and not `"uploaded"`), a size that's zero/negative or exceeds
 * [MAX_PLAUSIBLE_UPDATE_ASSET_SIZE_BYTES], or one with no verifiable `sha256` digest.
 * `.aab` is never matched (no [UpdateAssetKind] suffix ends in `.aab`) — it is a Play submission
 * format, not something [works.merc.keryx.app.domain.UpdateInstaller] can install.
 */
internal fun selectUpdateAsset(assets: List<ReleaseAsset>, location: InstallLocation): UpdateAsset? {
    val kind = assetKindFor(location) ?: return null
    val namePattern = ASSET_NAME_PATTERN_BY_KIND.getValue(kind)
    val candidate = assets.firstOrNull { a ->
        (a.state == null || a.state == "uploaded") && namePattern.matches(a.name) &&
            a.sizeBytes in 1..MAX_PLAUSIBLE_UPDATE_ASSET_SIZE_BYTES
    } ?: return null
    val sha256 = parseSha256Digest(candidate.digest) ?: return null
    return UpdateAsset(candidate.name, candidate.downloadUrl, candidate.sizeBytes, sha256, kind)
}
