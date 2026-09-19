package works.merc.keryx.app.domain

import works.merc.keryx.app.data.remote.ReleaseAsset
import works.merc.keryx.app.platform.HostArchitecture
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun location(kind: InstallKind) =
    InstallLocation(kind, appRoot = null, launcherPath = null, parentWritable = true, translocated = false)

/** A realistic stable-release asset set, mirroring `Keryx-0.13.0`'s actual GitHub API response
 * (now including the arm64 Linux assets `release.yml` publishes alongside the x86_64 ones). */
private fun stableReleaseAssets(sha256: String = "a".repeat(64)) = listOf(
    ReleaseAsset("Keryx-0.13.0-android-universal.aab", "https://x/aab", 1L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-android-universal.apk", "https://x/apk", 2L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-x86_64.deb", "https://x/deb", 3L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-x86_64.rpm", "https://x/rpm", 4L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-x86_64.zip", "https://x/linuxzip", 5L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-arm64.deb", "https://x/deb-arm64", 10L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-arm64.rpm", "https://x/rpm-arm64", 11L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-arm64.zip", "https://x/linuxzip-arm64", 12L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-macos-arm64.dmg", "https://x/dmg", 6L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x/maczip", 7L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-windows-x86_64.msi", "https://x/msi", 8L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-windows-x86_64.zip", "https://x/winzip", 9L, "sha256:$sha256", "uploaded"),
)

/** A prerelease asset set never carries `.dmg`/`.deb`/`.rpm`/`.msi` — only the `.zip` app images and
 * the Android APK/AAB (see release.yml's own prerelease-skip logic). */
private fun prereleaseAssets(sha256: String = "b".repeat(64)) = listOf(
    ReleaseAsset("Keryx-0.13.0-android-universal.aab", "https://x/aab", 1L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-android-universal.apk", "https://x/apk", 2L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-x86_64.zip", "https://x/linuxzip", 5L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-linux-arm64.zip", "https://x/linuxzip-arm64", 12L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x/maczip", 7L, "sha256:$sha256", "uploaded"),
    ReleaseAsset("Keryx-0.13.0-windows-x86_64.zip", "https://x/winzip", 9L, "sha256:$sha256", "uploaded"),
)

/** A stable release whose arm64 Linux job failed to publish — the x86_64 Linux assets exist, but
 * no `-linux-arm64.*` ones do. Used to guard against an arm64 host falling back to the x86_64 zip. */
private fun stableReleaseAssetsMissingLinuxArm64(sha256: String = "a".repeat(64)) =
    stableReleaseAssets(sha256).filterNot { it.name.contains("-linux-arm64") }

class UpdateAssetSelectorTest {

    @Test
    fun macAppBundleAlwaysPicksTheZipNeverTheDmg() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-macos-arm64.zip", asset?.name)
        assertEquals(UpdateAssetKind.MAC_APP_ZIP, asset?.kind)
    }

    @Test
    fun macAppBundlePicksTheZipOnAPrereleaseToo() {
        val asset = selectUpdateAsset(prereleaseAssets(), location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-macos-arm64.zip", asset?.name)
    }

    @Test
    fun windowsInstalledPicksTheMsi() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.WINDOWS_INSTALLED), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-windows-x86_64.msi", asset?.name)
        assertEquals(UpdateAssetKind.WINDOWS_MSI, asset?.kind)
    }

    @Test
    fun windowsInstalledFindsNoAssetOnAPrereleaseMissingTheMsi() {
        assertNull(selectUpdateAsset(prereleaseAssets(), location(InstallKind.WINDOWS_INSTALLED), HostArchitecture.X86_64))
    }

    @Test
    fun windowsPortablePicksTheZip() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.WINDOWS_PORTABLE), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-windows-x86_64.zip", asset?.name)
        assertEquals(UpdateAssetKind.WINDOWS_ZIP, asset?.kind)
    }

    @Test
    fun linuxPortablePicksTheX86_64ZipOnAnX86_64Host() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.LINUX_PORTABLE), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-linux-x86_64.zip", asset?.name)
        assertEquals(UpdateAssetKind.LINUX_ZIP, asset?.kind)
    }

    @Test
    fun linuxPortablePicksTheArm64ZipOnAnArm64Host() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.LINUX_PORTABLE), HostArchitecture.ARM64)
        assertEquals("Keryx-0.13.0-linux-arm64.zip", asset?.name)
        assertEquals(UpdateAssetKind.LINUX_ZIP, asset?.kind)
    }

    /** Regression guard: an arm64 host must never fall back to the x86_64 zip just because a
     * release happens to carry one — a release whose arm64 job failed to publish must look like no
     * asset exists at all, not like an x86_64 binary is safe to run there. */
    @Test
    fun linuxPortableOnAnArm64HostFindsNothingWhenTheReleaseHasNoArm64Asset() {
        assertNull(selectUpdateAsset(stableReleaseAssetsMissingLinuxArm64(), location(InstallKind.LINUX_PORTABLE), HostArchitecture.ARM64))
    }

    /** A host whose architecture this project ships no asset for at all (e.g. 32-bit) must never
     * guess — not even when the release happens to carry both Linux zips. */
    @Test
    fun linuxPortableOnAnUnknownArchitectureNeverSelectsAnAsset() {
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.LINUX_PORTABLE), HostArchitecture.UNKNOWN))
    }

    /** [arch] only matters for [InstallKind.LINUX_PORTABLE] — every other kind ships a single
     * architecture only, so the same asset must be picked no matter the host's own architecture. */
    @Test
    fun archHasNoEffectOnNonLinuxInstallKinds() {
        for (arch in HostArchitecture.entries) {
            assertEquals(
                "Keryx-0.13.0-macos-arm64.zip",
                selectUpdateAsset(stableReleaseAssets(), location(InstallKind.MAC_APP_BUNDLE), arch)?.name,
                "arch=$arch",
            )
            assertEquals(
                "Keryx-0.13.0-windows-x86_64.msi",
                selectUpdateAsset(stableReleaseAssets(), location(InstallKind.WINDOWS_INSTALLED), arch)?.name,
                "arch=$arch",
            )
            assertEquals(
                "Keryx-0.13.0-windows-x86_64.zip",
                selectUpdateAsset(stableReleaseAssets(), location(InstallKind.WINDOWS_PORTABLE), arch)?.name,
                "arch=$arch",
            )
        }
    }

    @Test
    fun linuxPackageNeverSelectsAnAssetRegardlessOfWhatShipped() {
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.LINUX_PACKAGE), HostArchitecture.X86_64))
    }

    @Test
    fun linuxSnapNeverSelectsAnAssetRegardlessOfWhatShipped() {
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.LINUX_SNAP), HostArchitecture.X86_64))
    }

    @Test
    fun androidSideloadedPicksTheApkNeverTheAab() {
        val asset = selectUpdateAsset(stableReleaseAssets(), location(InstallKind.ANDROID_SIDELOADED), HostArchitecture.X86_64)
        assertEquals("Keryx-0.13.0-android-universal.apk", asset?.name)
        assertEquals(UpdateAssetKind.ANDROID_APK, asset?.kind)
    }

    @Test
    fun androidStoreNeverSelectsAnAsset() {
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.ANDROID_STORE), HostArchitecture.X86_64))
    }

    @Test
    fun developmentAndUnknownNeverSelectAnAsset() {
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.DEVELOPMENT), HostArchitecture.X86_64))
        assertNull(selectUpdateAsset(stableReleaseAssets(), location(InstallKind.UNKNOWN), HostArchitecture.X86_64))
    }

    @Test
    fun assetWithNoDigestIsNeverSelected() {
        val assets = listOf(ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", 1L, digest = null, state = "uploaded"))
        assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64))
    }

    @Test
    fun assetWithANonSha256DigestIsNeverSelected() {
        val assets = listOf(
            ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", 1L, digest = "md5:deadbeef", state = "uploaded"),
        )
        assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64))
    }

    @Test
    fun assetStillProcessingByGitHubIsNeverSelected() {
        val assets = listOf(
            ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", 1L, digest = "sha256:${"a".repeat(64)}", state = "open"),
        )
        assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64))
    }

    @Test
    fun assetWithNoStateFieldAtAllIsStillSelected() {
        // Older API responses (or hand-written test fixtures) may simply omit `state` — absence
        // is not the same as "known not uploaded", so it must not block selection.
        val assets = listOf(
            ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", 1L, digest = "sha256:${"a".repeat(64)}", state = null),
        )
        assertEquals("Keryx-0.13.0-macos-arm64.zip", selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64)?.name)
    }

    @Test
    fun noMatchingAssetNameYieldsNull() {
        val assets = listOf(ReleaseAsset("Keryx-0.13.0-ios.ipa", "https://x", 1L, "sha256:${"a".repeat(64)}", "uploaded"))
        assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64))
    }

    @Test
    fun assetAtOrBelowTheSizeCeilingIsSelected() {
        // 1 GiB, exactly at the ceiling — see selectUpdateAsset's own KDoc.
        val atCeiling = 1024L * 1024 * 1024
        val assets = listOf(
            ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", atCeiling, "sha256:${"a".repeat(64)}", "uploaded"),
        )
        assertEquals(atCeiling, selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64)?.sizeBytes)
    }

    /**
     * Regression guard: an implausibly large declared size (a compromised or malformed release
     * response) must never reach [hasEnoughFreeSpaceForUpdate]'s arithmetic, where it could overflow
     * a `Long` and read as "plenty of free space" no matter how little actually is.
     */
    @Test
    fun assetAboveTheSizeCeilingIsNeverSelected() {
        val justOverCeiling = 1024L * 1024 * 1024 + 1
        val assets = listOf(
            ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", justOverCeiling, "sha256:${"a".repeat(64)}", "uploaded"),
        )
        assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64))
    }

    @Test
    fun assetWithAZeroOrNegativeSizeIsNeverSelected() {
        for (size in listOf(0L, -1L, Long.MIN_VALUE)) {
            val assets = listOf(
                ReleaseAsset("Keryx-0.13.0-macos-arm64.zip", "https://x", size, "sha256:${"a".repeat(64)}", "uploaded"),
            )
            assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64), "size=$size must not be selected")
        }
    }

    /**
     * Regression guard: [UpdateAsset.name] ends up as a path component (UpdateRepository's
     * `destPath`), so a release asset name containing a path separator or traversal sequence must
     * be rejected outright — the old prefix/suffix-only check let anything through in between.
     */
    @Test
    fun assetNameWithAPathSeparatorInTheMiddleIsNeverSelected() {
        val maliciousNames = listOf(
            "Keryx-../../../Library/LaunchAgents/x-macos-arm64.zip",
            "Keryx-2.0.0/../../evil-macos-arm64.zip",
            "Keryx-2.0.0\\..\\evil-macos-arm64.zip",
        )
        for (name in maliciousNames) {
            val assets = listOf(ReleaseAsset(name, "https://x", 1_000, "sha256:${"a".repeat(64)}", "uploaded"))
            assertNull(selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64), "name=$name must not be selected")
        }
    }

    @Test
    fun assetNameWithAnOrdinaryVersionMiddleIsStillSelected() {
        // The tightened pattern must not become so strict it rejects real release asset names.
        val assets = listOf(
            ReleaseAsset("Keryx-2.0.0-rc.1+build.5-macos-arm64.zip", "https://x", 1_000, "sha256:${"a".repeat(64)}", "uploaded"),
        )
        assertEquals(
            "Keryx-2.0.0-rc.1+build.5-macos-arm64.zip",
            selectUpdateAsset(assets, location(InstallKind.MAC_APP_BUNDLE), HostArchitecture.X86_64)?.name,
        )
    }

    // --- parseSha256Digest ---

    @Test
    fun parseSha256DigestExtractsTheLowerCaseHex() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, parseSha256Digest("sha256:$hex"))
    }

    @Test
    fun parseSha256DigestRejectsOtherAlgorithms() {
        assertNull(parseSha256Digest("md5:${"a".repeat(32)}"))
        assertNull(parseSha256Digest("sha1:${"a".repeat(40)}"))
    }

    @Test
    fun parseSha256DigestRejectsWrongLengthOrUppercaseHex() {
        assertNull(parseSha256Digest("sha256:${"a".repeat(63)}")) // too short
        assertNull(parseSha256Digest("sha256:${"A".repeat(64)}")) // uppercase — GitHub's own digest is lower-case
    }

    @Test
    fun parseSha256DigestRejectsMalformedOrMissingInput() {
        assertNull(parseSha256Digest(null))
        assertNull(parseSha256Digest(""))
        assertNull(parseSha256Digest("not-a-digest-at-all"))
    }
}
