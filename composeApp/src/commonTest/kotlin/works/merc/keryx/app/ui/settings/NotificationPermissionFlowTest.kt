package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.mutableStateOf
import works.merc.keryx.app.platform.NotificationPermissionController
import works.merc.keryx.app.ui.settings.NotificationPermissionEffect.RequestPermission
import works.merc.keryx.app.ui.settings.NotificationPermissionEffect.SetNotificationEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [NotificationPermissionFlow] decides when the in-app explanation appears and keeps the
 * `notificationEnabled` setting in line with the OS permission, whichever route asked.
 */
class NotificationPermissionFlowTest {

    private val initial = NotificationPermissionPromptState()

    // --- Home's one-time check ---

    @Test
    fun aPlatformWithoutARuntimePermissionNeverPrompts() {
        // Desktop and Android below 13 always report the permission as granted.
        val step = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = true)

        assertFalse(step.state.explanationVisible)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun aGrantedPermissionNeverPrompts() {
        val step = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = true)

        assertFalse(step.state.explanationVisible)
        assertTrue(step.state.homeChecked)
    }

    @Test
    fun aSettingThatIsOffNeverPrompts() {
        val step = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = false, permissionGranted = false)

        assertFalse(step.state.explanationVisible)
        assertTrue(step.effects.isEmpty())
    }

    @Test
    fun theFirstHomeShowPromptsWhenTheSettingIsOnButThePermissionIsNot() {
        val step = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = false)

        assertTrue(step.state.explanationVisible)
        assertTrue(step.effects.isEmpty(), "the explanation comes before any system request")
    }

    @Test
    fun homePromptsOnlyOnce() {
        val shown = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = false)
        val answered = NotificationPermissionFlow.onAllow(shown.state)

        // e.g. the Settings dialog later re-enters Home's check while the request is in flight.
        val again = NotificationPermissionFlow.onHomeShown(answered.state, notificationEnabled = true, permissionGranted = false)

        assertFalse(again.state.explanationVisible)
        assertTrue(again.effects.isEmpty())
    }

    // --- Answering the explanation ---

    @Test
    fun allowHidesTheExplanationAndAsksTheSystem() {
        val shown = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = false)
        val step = NotificationPermissionFlow.onAllow(shown.state)

        assertFalse(step.state.explanationVisible)
        assertEquals(listOf(RequestPermission), step.effects)
    }

    @Test
    fun notNowTurnsTheSettingOff() {
        val shown = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = true, permissionGranted = false)
        val step = NotificationPermissionFlow.onNotNow(shown.state)

        assertFalse(step.state.explanationVisible)
        assertEquals(listOf(SetNotificationEnabled(false)), step.effects)
    }

    @Test
    fun aDeniedRequestTurnsTheSettingOff() {
        val step = NotificationPermissionFlow.onPermissionResult(initial, granted = false)

        assertEquals(listOf(SetNotificationEnabled(false)), step.effects)
        assertTrue(step.state.lastRequestDenied)
    }

    @Test
    fun aGrantedRequestKeepsTheSettingOn() {
        val step = NotificationPermissionFlow.onPermissionResult(initial.copy(lastRequestDenied = true), granted = true)

        assertEquals(listOf(SetNotificationEnabled(true)), step.effects)
        assertFalse(step.state.lastRequestDenied)
    }

    @Test
    fun aSettingAlreadyTurnedOffDoesNotPromptOnTheNextLaunch() {
        // "Not now"/denial turned the setting off; a fresh launch starts from a fresh state.
        val nextLaunch = NotificationPermissionFlow.onHomeShown(initial, notificationEnabled = false, permissionGranted = false)

        assertFalse(nextLaunch.state.explanationVisible)
    }

    // --- The Settings switch ---

    @Test
    fun switchingOnSkipsTheExplanationAndAsksTheSystemDirectly() {
        val step = NotificationPermissionFlow.onSwitchChanged(initial, enabled = true, permissionGranted = false)

        assertFalse(step.state.explanationVisible)
        assertEquals(listOf(RequestPermission), step.effects, "the setting turns on only once granted")
    }

    @Test
    fun switchingOnWithThePermissionGrantedWritesTheSettingDirectly() {
        val step = NotificationPermissionFlow.onSwitchChanged(initial, enabled = true, permissionGranted = true)

        assertEquals(listOf(SetNotificationEnabled(true)), step.effects)
    }

    @Test
    fun switchingOffNeverAsksTheSystem() {
        val step = NotificationPermissionFlow.onSwitchChanged(initial, enabled = false, permissionGranted = false)

        assertEquals(listOf(SetNotificationEnabled(false)), step.effects)
    }

    @Test
    fun theSystemSettingsHintShowsOnlyAfterADenialWhileStillRefused() {
        assertFalse(NotificationPermissionFlow.showsSystemSettingsHint(initial, permissionGranted = false))

        val denied = NotificationPermissionFlow.onPermissionResult(initial, granted = false).state
        assertTrue(NotificationPermissionFlow.showsSystemSettingsHint(denied, permissionGranted = false))
        // Granted from the OS settings and back: the hint goes away.
        assertFalse(NotificationPermissionFlow.showsSystemSettingsHint(denied, permissionGranted = true))
    }

    // --- NotificationPermissionPrompt wiring ---

    @Test
    fun promptCarriesOutTheHomeFlowAgainstThePlatform() {
        val settingWrites = mutableListOf<Boolean>()
        val permission = FakePermission(granted = false, answer = false)
        val prompt = NotificationPermissionPrompt(mutableStateOf(NotificationPermissionPromptState())) { settingWrites += it }
        prompt.permission = permission
        permission.onResult = prompt::onPermissionResult

        prompt.onHomeShown(notificationEnabled = true)
        assertTrue(prompt.explanationVisible)

        prompt.onAllow()
        assertFalse(prompt.explanationVisible)
        assertEquals(1, permission.requests)
        assertEquals(listOf(false), settingWrites, "a denial turns the setting off")
        assertTrue(prompt.systemSettingsHintVisible)
    }

    @Test
    fun promptWithAGrantedPermissionWritesTheSwitchWithoutAsking() {
        val settingWrites = mutableListOf<Boolean>()
        val permission = FakePermission(granted = true, answer = true)
        val prompt = NotificationPermissionPrompt(mutableStateOf(NotificationPermissionPromptState())) { settingWrites += it }
        prompt.permission = permission

        prompt.onHomeShown(notificationEnabled = true)
        prompt.onSwitchChanged(true)

        assertFalse(prompt.explanationVisible)
        assertEquals(0, permission.requests)
        assertEquals(listOf(true), settingWrites)
    }

    private class FakePermission(granted: Boolean, private val answer: Boolean) : NotificationPermissionController {
        override var isGranted: Boolean = granted
        var requests = 0
        var onResult: (Boolean) -> Unit = {}

        override fun request() {
            requests++
            isGranted = answer
            onResult(answer)
        }

        override fun openSystemSettings() = Unit
    }
}
