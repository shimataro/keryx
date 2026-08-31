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
 * fixed (see the plan's "背景" section): the provider name wrapping onto four lines at a phone
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
    private val disconnectLabel = "連携を解除"

    /** A connected OneDrive row — the screenshot that motivated this change used OneDrive. */
    @Composable
    private fun ConnectedOneDriveRow(
        iconOnly: Boolean,
        resetting: Boolean = false,
        idleEnabled: Boolean = true,
    ) {
        CloudProviderRow(
            type = CloudStorageType.ONEDRIVE,
            connected = true,
            connecting = false,
            canCancel = false,
            idleEnabled = idleEnabled,
            failed = false,
            resetting = resetting,
            iconOnly = iconOnly,
            onSelect = {},
            onCancel = {},
            onDisconnect = {},
            onResetCloudData = {},
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
