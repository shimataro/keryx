package works.merc.keryx.app.platform

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.testContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Covers the PR #210 review finding that [AndroidAuthorizationHost.launch]'s `finally` used to
 * clear the pending slot on *any* exit, cancellation included. Cancelling the waiting coroutine
 * does not cancel an already-launched consent Activity, so the slot could be handed to a later
 * request that the earlier Activity's result would then complete — [AndroidAuthorizationHost.onResult]
 * has no request id to tell the two apart.
 *
 * Needs a device only for the real [PendingIntent]; everything else is a fake.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidAuthorizationHostDeviceTest {

    @AfterTest
    fun resetProcessWideState() {
        // The host is an `object`, so a leftover launcher or pending slot would leak into the next
        // test in this same instrumentation process.
        AndroidAuthorizationHost.detach(retainPending = false)
    }

    @Test
    fun keepsThePendingSlotWhenAnAlreadyLaunchedRequestIsCancelled() = runTest {
        val launcher = FakeIntentSenderLauncher()
        AndroidAuthorizationHost.attach(launcher)

        val waiting = launch { AndroidAuthorizationHost.launch(pendingIntent()) }
        runCurrent()
        assertEquals(1, launcher.launchCount)

        waiting.cancelAndJoin()

        // The slot is still occupied by the consent Activity that is notionally still running, so
        // the next request is rejected outright rather than being wired up to inherit its result.
        assertNull(AndroidAuthorizationHost.launch(pendingIntent()))
        assertEquals(1, launcher.launchCount)
    }

    @Test
    fun releasesThePendingSlotWhenTheLauncherItselfFails() = runTest {
        val launcher = FakeIntentSenderLauncher(failuresBeforeSuccess = 1)
        AndroidAuthorizationHost.attach(launcher)

        assertFailsWith<IllegalStateException> { AndroidAuthorizationHost.launch(pendingIntent()) }

        // A launch that never happened can never produce a result, so the slot must be free again.
        val waiting = launch { AndroidAuthorizationHost.launch(pendingIntent()) }
        runCurrent()
        assertEquals(2, launcher.launchCount)

        waiting.cancelAndJoin()
    }

    @Test
    fun releasesThePendingSlotOnceTheResultArrives() = runTest {
        val launcher = FakeIntentSenderLauncher()
        AndroidAuthorizationHost.attach(launcher)

        val waiting = launch { AndroidAuthorizationHost.launch(pendingIntent()) }
        runCurrent()
        waiting.cancelAndJoin()

        AndroidAuthorizationHost.onResult(ActivityResult(Activity.RESULT_CANCELED, null))

        val second = launch { AndroidAuthorizationHost.launch(pendingIntent()) }
        runCurrent()
        assertEquals(2, launcher.launchCount)

        second.cancelAndJoin()
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getActivity(
        testContext(),
        0,
        Intent(Intent.ACTION_VIEW).setPackage(testContext().packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Stands in for the `StartIntentSenderForResult` launcher `MainActivity` registers, counting calls
 * and optionally failing the first [failuresBeforeSuccess] of them the way a dead `IntentSender`
 * would.
 */
private class FakeIntentSenderLauncher(
    private val failuresBeforeSuccess: Int = 0,
) : ActivityResultLauncher<IntentSenderRequest>() {

    var launchCount = 0
        private set

    override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {
        launchCount++
        if (launchCount <= failuresBeforeSuccess) error("IntentSender could not be started")
    }

    override fun unregister() = Unit

    override val contract: ActivityResultContract<IntentSenderRequest, *> =
        ActivityResultContracts.StartIntentSenderForResult()
}
