package works.merc.keryx.app.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SettingsOpenRequests] is the single router every "open Settings" route goes through: it must
 * never let the settings dialog open over first-run Setup, while still delivering a request made
 * there once Home is showing.
 */
class SettingsOpenRequestsTest {

    @Test
    fun aRequestMadeDuringSetupIsHeldUntilHome() {
        val router = SettingsOpenRequests()
        router.request("updates")

        assertNull(router.release(Screen.Setup), "Settings must never open over Setup")
        assertEquals(SettingsOpenRequest("updates"), router.pending.value, "the request stays latched")

        assertEquals(SettingsOpenRequest("updates"), router.release(Screen.Home))
    }

    @Test
    fun releasingClearsTheRequestSoItOpensOnlyOnce() {
        val router = SettingsOpenRequests()
        router.request("data")

        assertEquals(SettingsOpenRequest("data"), router.release(Screen.Home))
        assertNull(router.pending.value)
        assertNull(router.release(Screen.Home))
    }

    @Test
    fun theLastRequestWins() {
        val router = SettingsOpenRequests()
        router.request("cloud_sync")
        router.request("data")

        assertEquals(SettingsOpenRequest("data"), router.release(Screen.Home))
        assertNull(router.release(Screen.Home))
    }

    @Test
    fun aRepeatedRequestForTheSameTabIsDeliveredAgainAfterRelease() {
        val router = SettingsOpenRequests()
        router.request("updates")
        router.release(Screen.Home)

        router.request("updates")

        assertEquals(SettingsOpenRequest("updates"), router.release(Screen.Home))
    }

    @Test
    fun requestIfReachableIsDroppedAwayFromHome() {
        val router = SettingsOpenRequests()

        assertFalse(router.requestIfReachable("general", Screen.Setup))
        assertNull(router.pending.value, "a direct gesture during Setup must not open Settings later")
        assertNull(router.release(Screen.Home))
    }

    @Test
    fun requestIfReachableIsAcceptedOnHome() {
        val router = SettingsOpenRequests()

        assertTrue(router.requestIfReachable("general", Screen.Home))
        assertEquals(SettingsOpenRequest("general"), router.release(Screen.Home))
    }
}
