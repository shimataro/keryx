package works.merc.keryx.app.domain

import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

private val WRITABLE_MAC_LOCATION = InstallLocation(
    InstallKind.MAC_APP_BUNDLE, appRoot = "/Applications/Keryx.app", launcherPath = null, parentWritable = true, translocated = false,
)

private val MAC_ASSET = UpdateAsset(
    "Keryx-2.0.0-macos-arm64.zip", "https://dl/mac.zip", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP,
)

private fun availableUpdate(version: String, asset: UpdateAsset? = MAC_ASSET) =
    AvailableUpdate(version, "https://ex.com/$version", null, asset, updatePlan(WRITABLE_MAC_LOCATION, asset))

class UpdateStateMachineTest {

    @Test
    fun idlePlusUpToDateBecomesUpToDate() {
        val next = nextStateAfterCheck(UpdateState.Idle, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION)
        assertEquals(UpdateState.UpToDate, next)
    }

    @Test
    fun idlePlusAvailableBecomesAvailableWithAPlanComputedFromLocation() {
        val next = nextStateAfterCheck(
            UpdateState.Idle, UpdateStatus.Available("2.0.0", "https://ex.com/2.0.0", null, MAC_ASSET), WRITABLE_MAC_LOCATION,
        )
        assertIs<UpdateState.Available>(next)
        assertEquals("2.0.0", next.update.version)
        assertIs<UpdatePlan.SelfReplace>(next.update.plan)
    }

    @Test
    fun idlePlusFailedBecomesFailedWithNoUpdateAndTheCheckStage() {
        val next = nextStateAfterCheck(UpdateState.Idle, UpdateStatus.Failed, WRITABLE_MAC_LOCATION)
        assertIs<UpdateState.Failed>(next)
        assertNull(next.update)
        assertEquals(UpdateStage.CHECK, next.exception.stage)
    }

    @Test
    fun availableIsReplacedByANewerAvailable() {
        val current = UpdateState.Available(availableUpdate("1.5.0"))
        val next = nextStateAfterCheck(
            current, UpdateStatus.Available("2.0.0", "https://ex.com/2.0.0", null, MAC_ASSET), WRITABLE_MAC_LOCATION,
        )
        assertIs<UpdateState.Available>(next)
        assertEquals("2.0.0", next.update.version)
    }

    @Test
    fun availableFallsBackToUpToDateWhenTheReleaseIsNoLongerOffered() {
        val current = UpdateState.Available(availableUpdate("1.5.0"))
        val next = nextStateAfterCheck(current, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION)
        assertEquals(UpdateState.UpToDate, next)
    }

    @Test
    fun readySurvivesTheSameVersionReportedUpToDate() {
        // From UpdateChecker's point of view, the version this repository already downloaded now
        // looks like "the current one" once the endpoint it hits reflects it, i.e. UpToDate.
        val ready = UpdateState.Ready(availableUpdate("2.0.0"), filePath = "/tmp/Keryx-2.0.0.zip")
        val next = nextStateAfterCheck(ready, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION)
        assertSame(ready, next)
    }

    @Test
    fun readySurvivesACheckFailure() {
        val ready = UpdateState.Ready(availableUpdate("2.0.0"), filePath = "/tmp/Keryx-2.0.0.zip")
        val next = nextStateAfterCheck(ready, UpdateStatus.Failed, WRITABLE_MAC_LOCATION)
        assertSame(ready, next)
    }

    @Test
    fun readySurvivesTheSameVersionReportedAvailableAgain() {
        val ready = UpdateState.Ready(availableUpdate("2.0.0"), filePath = "/tmp/Keryx-2.0.0.zip")
        val next = nextStateAfterCheck(
            ready, UpdateStatus.Available("2.0.0", "https://ex.com/2.0.0", null, MAC_ASSET), WRITABLE_MAC_LOCATION,
        )
        assertSame(ready, next)
    }

    @Test
    fun readyIsReplacedByAGenuinelyNewerVersion() {
        val ready = UpdateState.Ready(availableUpdate("2.0.0"), filePath = "/tmp/Keryx-2.0.0.zip")
        val next = nextStateAfterCheck(
            ready, UpdateStatus.Available("3.0.0", "https://ex.com/3.0.0", null, MAC_ASSET), WRITABLE_MAC_LOCATION,
        )
        assertIs<UpdateState.Available>(next)
        assertEquals("3.0.0", next.update.version)
    }

    @Test
    fun downloadingIsNeverInterruptedByAConcurrentCheck() {
        val downloading = UpdateState.Downloading(availableUpdate("2.0.0"), bytesDone = 10, bytesTotal = 100)
        for (status in listOf(UpdateStatus.UpToDate, UpdateStatus.Failed, UpdateStatus.Available("3.0.0", "https://ex.com/3.0.0", null, MAC_ASSET))) {
            assertSame(downloading, nextStateAfterCheck(downloading, status, WRITABLE_MAC_LOCATION))
        }
    }

    @Test
    fun verifyingIsNeverInterruptedByAConcurrentCheck() {
        val verifying = UpdateState.Verifying(availableUpdate("2.0.0"))
        assertSame(verifying, nextStateAfterCheck(verifying, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION))
    }

    @Test
    fun installingIsNeverInterruptedByAConcurrentCheck() {
        val installing = UpdateState.Installing(availableUpdate("2.0.0"))
        assertSame(installing, nextStateAfterCheck(installing, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION))
    }

    @Test
    fun failedRecoversIntoAvailableWhenACheckSucceeds() {
        val failed = UpdateState.Failed(availableUpdate("2.0.0"), UpdateException(UpdateStage.DOWNLOAD, "boom"))
        val next = nextStateAfterCheck(
            failed, UpdateStatus.Available("2.0.0", "https://ex.com/2.0.0", null, MAC_ASSET), WRITABLE_MAC_LOCATION,
        )
        assertIs<UpdateState.Available>(next)
        assertEquals("2.0.0", next.update.version)
    }

    @Test
    fun failedWithNoUpdateBecomesUpToDateWhenACheckSucceeds() {
        val failed = UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "boom"))
        val next = nextStateAfterCheck(failed, UpdateStatus.UpToDate, WRITABLE_MAC_LOCATION)
        assertEquals(UpdateState.UpToDate, next)
    }
}
