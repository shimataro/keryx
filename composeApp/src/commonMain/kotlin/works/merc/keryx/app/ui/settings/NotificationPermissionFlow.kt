package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import works.merc.keryx.app.platform.NotificationPermissionController
import works.merc.keryx.app.platform.rememberNotificationPermission

/**
 * Per-call-site state of the notification-permission flow (see [NotificationPermissionFlow]).
 *
 * @property homeChecked Home's one-time check has run; it never re-runs for this composition.
 * @property explanationVisible The in-app explanation dialog is showing.
 * @property lastRequestDenied The last system request made from here was denied.
 */
internal data class NotificationPermissionPromptState(
    val homeChecked: Boolean = false,
    val explanationVisible: Boolean = false,
    val lastRequestDenied: Boolean = false,
)

/** A side effect a [NotificationPermissionFlow] transition asks its caller to carry out. */
internal sealed interface NotificationPermissionEffect {
    /** Launch the OS permission request. */
    data object RequestPermission : NotificationPermissionEffect

    /** Write the device-local `notificationEnabled` setting. */
    data class SetNotificationEnabled(val enabled: Boolean) : NotificationPermissionEffect
}

/** The outcome of one [NotificationPermissionFlow] transition. */
internal data class NotificationPermissionStep(
    val state: NotificationPermissionPromptState,
    val effects: List<NotificationPermissionEffect> = emptyList(),
)

/**
 * The decisions behind asking for the OS notification permission (Android 13+), kept free of
 * Compose and platform code so they can be unit-tested. The rule is that the user's
 * `notificationEnabled` setting follows the permission: it is never left on while the permission
 * is refused.
 *
 * Two routes lead to a request, both through here:
 * - **Home, once**: when Home is first shown with the setting on but the permission not granted,
 *   an in-app explanation is shown first; "Allow" goes on to the system request, "Not now" turns
 *   the setting off (so the explanation does not come back on every launch).
 * - **Settings ▸ Notifications switch**: turning it on is context enough, so it goes straight to
 *   the system request; the setting is turned on only once the permission is granted.
 *
 * Where there is no runtime permission (desktop, Android below 13) the permission always reads as
 * granted, so no explanation is ever shown and the switch writes the setting directly.
 */
internal object NotificationPermissionFlow {
    /** Home was shown. Decides, once per composition, whether to show the explanation. */
    fun onHomeShown(
        state: NotificationPermissionPromptState,
        notificationEnabled: Boolean,
        permissionGranted: Boolean,
    ): NotificationPermissionStep {
        if (state.homeChecked) return NotificationPermissionStep(state)
        return NotificationPermissionStep(
            state.copy(homeChecked = true, explanationVisible = notificationEnabled && !permissionGranted),
        )
    }

    /** "Allow" in the explanation: hide it and ask the OS. */
    fun onAllow(state: NotificationPermissionPromptState): NotificationPermissionStep = NotificationPermissionStep(
        state.copy(explanationVisible = false),
        listOf(NotificationPermissionEffect.RequestPermission),
    )

    /** "Not now" (or any other dismissal) of the explanation: turn the setting off to match. */
    fun onNotNow(state: NotificationPermissionPromptState): NotificationPermissionStep = NotificationPermissionStep(
        state.copy(explanationVisible = false),
        listOf(NotificationPermissionEffect.SetNotificationEnabled(false)),
    )

    /** The Settings switch was flipped to [enabled]. Turning it on asks the OS first, unless already granted. */
    fun onSwitchChanged(
        state: NotificationPermissionPromptState,
        enabled: Boolean,
        permissionGranted: Boolean,
    ): NotificationPermissionStep = when {
        !enabled -> NotificationPermissionStep(state, listOf(NotificationPermissionEffect.SetNotificationEnabled(false)))
        permissionGranted -> NotificationPermissionStep(
            state.copy(lastRequestDenied = false),
            listOf(NotificationPermissionEffect.SetNotificationEnabled(true)),
        )
        else -> NotificationPermissionStep(state, listOf(NotificationPermissionEffect.RequestPermission))
    }

    /** The OS answered a request: the setting follows the answer. */
    fun onPermissionResult(state: NotificationPermissionPromptState, granted: Boolean): NotificationPermissionStep =
        NotificationPermissionStep(
            state.copy(lastRequestDenied = !granted),
            listOf(NotificationPermissionEffect.SetNotificationEnabled(granted)),
        )

    /**
     * Whether Settings shows the "allow it in the OS settings" hint: after a denial, for as long as
     * the permission is still refused. A permanent denial returns at once without any system
     * dialog, so without this the switch would just snap back off with no explanation.
     */
    fun showsSystemSettingsHint(state: NotificationPermissionPromptState, permissionGranted: Boolean): Boolean =
        state.lastRequestDenied && !permissionGranted
}

/**
 * Runs [NotificationPermissionFlow] against the platform's [NotificationPermissionController] and
 * the `notificationEnabled` setting. Obtain one with [rememberNotificationPermissionPrompt].
 */
@Stable
internal class NotificationPermissionPrompt(
    private val state: MutableState<NotificationPermissionPromptState>,
    private val setNotificationEnabled: (Boolean) -> Unit,
) {
    /** Assigned on every composition by [rememberNotificationPermissionPrompt] (or directly by a test). */
    lateinit var permission: NotificationPermissionController

    val explanationVisible: Boolean get() = state.value.explanationVisible

    val systemSettingsHintVisible: Boolean
        get() = NotificationPermissionFlow.showsSystemSettingsHint(state.value, permission.isGranted)

    fun onHomeShown(notificationEnabled: Boolean) =
        apply(NotificationPermissionFlow.onHomeShown(state.value, notificationEnabled, permission.isGranted))

    fun onAllow() = apply(NotificationPermissionFlow.onAllow(state.value))

    fun onNotNow() = apply(NotificationPermissionFlow.onNotNow(state.value))

    fun onSwitchChanged(enabled: Boolean) =
        apply(NotificationPermissionFlow.onSwitchChanged(state.value, enabled, permission.isGranted))

    fun onPermissionResult(granted: Boolean) = apply(NotificationPermissionFlow.onPermissionResult(state.value, granted))

    fun openSystemSettings() = permission.openSystemSettings()

    private fun apply(step: NotificationPermissionStep) {
        state.value = step.state
        step.effects.forEach { effect ->
            when (effect) {
                NotificationPermissionEffect.RequestPermission -> permission.request()
                is NotificationPermissionEffect.SetNotificationEnabled -> setNotificationEnabled(effect.enabled)
            }
        }
    }
}

private val promptStateSaver = listSaver<NotificationPermissionPromptState, Boolean>(
    save = { listOf(it.homeChecked, it.explanationVisible, it.lastRequestDenied) },
    restore = { NotificationPermissionPromptState(it[0], it[1], it[2]) },
)

/**
 * Remembers a [NotificationPermissionPrompt] whose state (including an open explanation dialog)
 * survives an Android configuration change.
 */
@Composable
internal fun rememberNotificationPermissionPrompt(setNotificationEnabled: (Boolean) -> Unit): NotificationPermissionPrompt {
    val state = rememberSaveable(stateSaver = promptStateSaver) { mutableStateOf(NotificationPermissionPromptState()) }
    val currentSetNotificationEnabled by rememberUpdatedState(setNotificationEnabled)
    val prompt = remember(state) { NotificationPermissionPrompt(state) { currentSetNotificationEnabled(it) } }
    prompt.permission = rememberNotificationPermission(onResult = prompt::onPermissionResult)
    return prompt
}
