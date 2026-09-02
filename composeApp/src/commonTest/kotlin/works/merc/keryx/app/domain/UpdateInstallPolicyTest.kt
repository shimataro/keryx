package works.merc.keryx.app.domain

import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val SOME_ASSET =
    UpdateAsset("Keryx-0.14.0-macos-arm64.zip", "https://x", 1L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)

private fun location(
    kind: InstallKind,
    parentWritable: Boolean = true,
    translocated: Boolean = false,
) = InstallLocation(kind, appRoot = "/some/path", launcherPath = "/some/path/launcher", parentWritable, translocated)

class UpdateInstallPolicyTest {

    @Test
    fun developmentAndroidStoreAndUnknownAreNeverOffered() {
        assertEquals(UpdatePlan.NotOffered, updatePlan(location(InstallKind.DEVELOPMENT), SOME_ASSET))
        assertEquals(UpdatePlan.NotOffered, updatePlan(location(InstallKind.ANDROID_STORE), SOME_ASSET))
        assertEquals(UpdatePlan.NotOffered, updatePlan(location(InstallKind.UNKNOWN), SOME_ASSET))
    }

    @Test
    fun notOfferedEvenWithNoAssetAtAll() {
        assertEquals(UpdatePlan.NotOffered, updatePlan(location(InstallKind.DEVELOPMENT), null))
    }

    @Test
    fun linuxPackageAlwaysOpensTheReleasePageRegardlessOfAsset() {
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.LINUX_PACKAGE), SOME_ASSET))
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.LINUX_PACKAGE), null))
    }

    @Test
    fun macAppBundleSelfReplacesWhenWritableAndNotTranslocated() {
        val plan = updatePlan(location(InstallKind.MAC_APP_BUNDLE), SOME_ASSET)
        assertIs<UpdatePlan.SelfReplace>(plan)
        assertEquals(SOME_ASSET, plan.asset)
    }

    @Test
    fun macAppBundleFallsBackToReleasePageWhenTranslocated() {
        val loc = location(InstallKind.MAC_APP_BUNDLE, translocated = true)
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(loc, SOME_ASSET))
    }

    @Test
    fun macAppBundleFallsBackToReleasePageWhenParentUnwritable() {
        val loc = location(InstallKind.MAC_APP_BUNDLE, parentWritable = false)
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(loc, SOME_ASSET))
    }

    @Test
    fun macAppBundleFallsBackToReleasePageWhenNoAsset() {
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.MAC_APP_BUNDLE), null))
    }

    @Test
    fun windowsAndLinuxPortableSelfReplaceWhenWritable() {
        assertIs<UpdatePlan.SelfReplace>(updatePlan(location(InstallKind.WINDOWS_PORTABLE), SOME_ASSET))
        assertIs<UpdatePlan.SelfReplace>(updatePlan(location(InstallKind.LINUX_PORTABLE), SOME_ASSET))
    }

    @Test
    fun windowsAndLinuxPortableFallBackWhenUnwritableOrNoAsset() {
        val unwritable = location(InstallKind.WINDOWS_PORTABLE, parentWritable = false)
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(unwritable, SOME_ASSET))
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.LINUX_PORTABLE), null))
    }

    @Test
    fun windowsInstalledRunsTheInstallerWhenAnAssetExists() {
        val plan = updatePlan(location(InstallKind.WINDOWS_INSTALLED), SOME_ASSET)
        assertIs<UpdatePlan.RunInstaller>(plan)
        assertEquals(SOME_ASSET, plan.asset)
    }

    @Test
    fun windowsInstalledFallsBackWhenNoAsset() {
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.WINDOWS_INSTALLED), null))
    }

    @Test
    fun androidSideloadedRunsTheInstallerWhenAnAssetExists() {
        val plan = updatePlan(location(InstallKind.ANDROID_SIDELOADED), SOME_ASSET)
        assertIs<UpdatePlan.RunInstaller>(plan)
    }

    @Test
    fun androidSideloadedFallsBackWhenNoAsset() {
        assertEquals(UpdatePlan.OpenReleasePage, updatePlan(location(InstallKind.ANDROID_SIDELOADED), null))
    }
}
