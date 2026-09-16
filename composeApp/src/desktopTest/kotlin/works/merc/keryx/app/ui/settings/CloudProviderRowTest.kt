package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import works.merc.keryx.app.core.CloudStorageType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the two Android bugs `ProviderActionButton`/`CloudProviderRow.iconOnly`
 * fixed (see the plan's "background" section): the provider name wrapping onto four lines at a phone
 * width once two labelled buttons squeezed it, and a connected row's action buttons having no
 * visible container against the row's own `secondaryContainer` tint.
 *
 * `CloudProviderRow` is exercised directly (it is `internal`, mirroring `ArticleRowMetadataTest`'s
 * direct render of the internal `ArticleRow`) rather than through `CloudSyncTabContent`, which
 * would need a full `SettingsViewModel` fixture.
 */
@OptIn(ExperimentalTestApi::class)
class CloudProviderRowTest {

    private val resetLabel = "同期データをリセット"
    private val reconnectLabel = "連携し直す"
    private val disconnectLabel = "連携を解除"

    /** A connected OneDrive row — the screenshot that motivated this change used OneDrive. */
    @Composable
    private fun ConnectedOneDriveRow(
        iconOnly: Boolean,
        resetting: Boolean = false,
        idleEnabled: Boolean = true,
        authFailed: Boolean = false,
    ) {
        CloudProviderRow(
            type = CloudStorageType.ONEDRIVE,
            connected = true,
            connecting = false,
            canCancel = false,
            idleEnabled = idleEnabled,
            failed = false,
            authFailed = authFailed,
            resetting = resetting,
            iconOnly = iconOnly,
            onSelect = {},
            onCancel = {},
            onDisconnect = {},
            onResetCloudData = {},
            onReconnect = {},
        )
    }

    @Test
    fun providerNameStaysOneLineRegardlessOfWidth() = runDesktopComposeUiTest {
        setContent {
            Column {
                Box(Modifier.width(288.dp)) { ConnectedOneDriveRow(iconOnly = true) }
                Box(Modifier.width(640.dp)) { ConnectedOneDriveRow(iconOnly = true) }
            }
        }
        waitForIdle()

        // With the old two-labelled-button layout this name wrapped onto four lines at 288dp,
        // roughly quadrupling its measured height versus the unconstrained 640dp render.
        val names = onAllNodesWithText("OneDrive")
        names.assertCountEquals(2)
        val narrowHeight = names[0].getBoundsInRoot().height
        val wideHeight = names[1].getBoundsInRoot().height
        assertEquals(narrowHeight, wideHeight)
    }

    @Test
    fun iconOnlyRowAtPhoneWidthExposesBothActionsByContentDescription() = runDesktopComposeUiTest {
        setContent {
            Box(Modifier.width(288.dp)) { ConnectedOneDriveRow(iconOnly = true) }
        }
        waitForIdle()

        onNodeWithContentDescription(resetLabel).assertIsDisplayed()
        onNodeWithContentDescription(disconnectLabel).assertIsDisplayed()
    }

    @Test
    fun labelledRowKeepsVisibleTextOnDesktop() = runDesktopComposeUiTest {
        setContent {
            Box(Modifier.width(640.dp)) { ConnectedOneDriveRow(iconOnly = false) }
        }
        waitForIdle()

        onNodeWithText(resetLabel).assertIsDisplayed()
        onNodeWithText(disconnectLabel).assertIsDisplayed()
    }

    /**
     * While sync is failing to authenticate, the row's recovery slot must offer reconnecting
     * instead of resetting the cloud data. The two swap rather than sitting side by side — a third
     * button would change the row's child count with state and overrun the width the labelled
     * buttons share with the provider name — and they are mutually exclusive anyway: a reset needs
     * a working authorization of its own, so it is the wrong recovery to offer while authorization
     * is exactly what broke.
     */
    @Test
    fun authFailureSwapsTheResetActionForReconnect() = runDesktopComposeUiTest {
        setContent {
            Box(Modifier.width(640.dp)) { ConnectedOneDriveRow(iconOnly = false, authFailed = true) }
        }
        waitForIdle()

        onNodeWithText(reconnectLabel).assertIsDisplayed()
        onAllNodesWithText(resetLabel).assertCountEquals(0)
    }

    @Test
    fun healthyRowKeepsTheResetActionAndOffersNoReconnect() = runDesktopComposeUiTest {
        setContent {
            Box(Modifier.width(640.dp)) { ConnectedOneDriveRow(iconOnly = false, authFailed = false) }
        }
        waitForIdle()

        onNodeWithText(resetLabel).assertIsDisplayed()
        onAllNodesWithText(reconnectLabel).assertCountEquals(0)
    }

    /**
     * Disconnect survives the swap above. Repairing and leaving are separate decisions, and a user
     * who wants to stop syncing must never have to authorize again first just to reach the exit.
     */
    @Test
    fun disconnectStaysAvailableWhetherOrNotAuthenticationFailed() = runDesktopComposeUiTest {
        setContent {
            Column {
                Box(Modifier.testTag("healthy").width(640.dp)) {
                    ConnectedOneDriveRow(iconOnly = false, authFailed = false)
                }
                Box(Modifier.testTag("failed").width(640.dp)) {
                    ConnectedOneDriveRow(iconOnly = false, authFailed = true)
                }
            }
        }
        waitForIdle()

        onNode(hasText(disconnectLabel) and hasAnyAncestor(hasTestTag("healthy"))).assertIsDisplayed()
        onNode(hasText(disconnectLabel) and hasAnyAncestor(hasTestTag("failed"))).assertIsDisplayed()
    }

    @Test
    fun resettingDisablesResetActionWithoutChangingRowHeight() = runDesktopComposeUiTest {
        setContent {
            Column {
                Box(Modifier.testTag("idle").width(640.dp)) {
                    ConnectedOneDriveRow(iconOnly = false, resetting = false)
                }
                Box(Modifier.testTag("resetting").width(640.dp)) {
                    ConnectedOneDriveRow(iconOnly = false, resetting = true)
                }
            }
        }
        waitForIdle()

        val idleResetButton = onNode(hasText(resetLabel) and hasAnyAncestor(hasTestTag("idle")))
        val resettingResetButton = onNode(hasText(resetLabel) and hasAnyAncestor(hasTestTag("resetting")))
        idleResetButton.assertIsEnabled()
        resettingResetButton.assertIsNotEnabled()

        // The busy spinner swaps in for the glyph in the same fixed slot — it must not reflow the row.
        val idleHeight = onNodeWithTag("idle").getBoundsInRoot().height
        val resettingHeight = onNodeWithTag("resetting").getBoundsInRoot().height
        assertEquals(idleHeight, resettingHeight)
    }
}
