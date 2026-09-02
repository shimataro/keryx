package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals

private val SOME_ASSET =
    UpdateAsset("Keryx-2.0.0-macos-arm64.zip", "https://x", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)

private fun installableUpdate(version: String = "2.0.0") =
    AvailableUpdate(version, "https://ex.com/$version", "- new stuff", SOME_ASSET, UpdatePlan.SelfReplace(SOME_ASSET))

private fun manualOnlyUpdate(version: String = "2.0.0") =
    AvailableUpdate(version, "https://ex.com/$version", null, null, UpdatePlan.OpenReleasePage)

/** A plan that would self-replace, but the platform currently refuses it (Android's
 * install-unknown-apps consent, most notably) — see [AvailableUpdate.installable]'s own KDoc. */
private fun platformRefusedUpdate(version: String = "2.0.0") =
    AvailableUpdate(version, "https://ex.com/$version", null, SOME_ASSET, UpdatePlan.SelfReplace(SOME_ASSET), installable = false)

private fun noop() = Unit

/**
 * Exercises [UpdateResultSection] directly against a bare [UpdateState] — no [SettingsViewModel]/
 * DI wiring needed, since it takes plain callbacks (see that function's own KDoc).
 */
@OptIn(ExperimentalTestApi::class)
class UpdatesTabTest {

    @Test
    fun upToDateShowsTheUpToDateMessageAndNoActionButton() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.UpToDate, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("最新版です").assertIsDisplayed()
        onAllNodesWithText("ダウンロード").assertCountEquals(0)
    }

    @Test
    fun availableInstallableShowsAnEnabledDownloadButton() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Available(installableUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("新しいバージョン 2.0.0 があります").assertIsDisplayed()
        onNodeWithText("ダウンロード").assertIsDisplayed().assertIsEnabled()
    }

    /**
     * Regression guard for this file's own design intent: the release notes are the *only* thing
     * inside [UPDATE_RELEASE_NOTES_CARD_TEST_TAG]'s card — the status/action headline is a plain,
     * unboxed banner above it, never sharing that frame (see `UpdatesTab.kt`'s own module KDoc).
     */
    @Test
    fun releaseNotesSitInsideTheirOwnCardWhileTheHeadlineDoesNot() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Available(installableUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNode(
            hasText("- new stuff") and hasAnyAncestor(hasTestTag(UPDATE_RELEASE_NOTES_CARD_TEST_TAG)),
        ).assertIsDisplayed()
        onAllNodes(
            hasText("新しいバージョン 2.0.0 があります") and hasAnyAncestor(hasTestTag(UPDATE_RELEASE_NOTES_CARD_TEST_TAG)),
        ).assertCountEquals(0)
    }

    @Test
    fun availableNotInstallableShowsNoDownloadButtonButLinksToTheReleasePage() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Available(manualOnlyUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onAllNodesWithText("ダウンロード").assertCountEquals(0)
        onNodeWithText("このインストール形態はアプリ内更新に対応していません").assertIsDisplayed()
        onNodeWithText("リリースページを開く").assertIsDisplayed()
    }

    /**
     * Regression guard: a plan that's technically self-replaceable but the platform actual
     * currently refuses ([AvailableUpdate.installable] `== false` despite [UpdatePlan.isInstallable]
     * being `true`) must read the same as [manualOnlyUpdate] here — no enabled "Download" that
     * [UpdateRepository.startDownload] would then silently no-op on.
     */
    @Test
    fun availablePlanInstallableButPlatformRefusedShowsNoDownloadButton() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Available(platformRefusedUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onAllNodesWithText("ダウンロード").assertCountEquals(0)
        onNodeWithText("このインストール形態はアプリ内更新に対応していません").assertIsDisplayed()
    }

    @Test
    fun clickingDownloadInvokesTheCallback() = runDesktopComposeUiTest {
        var invoked = false
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(
                    UpdateState.Available(installableUpdate()), ::noop, { invoked = true }, ::noop, ::noop,
                )
            }
        }
        waitForIdle()

        onNodeWithText("ダウンロード").performClick()

        assertEquals(true, invoked)
    }

    @Test
    fun downloadingShowsProgressPercentAndAnEnabledCancelButton() = runDesktopComposeUiTest {
        val state = UpdateState.Downloading(installableUpdate(), bytesDone = 50, bytesTotal = 100)
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("ダウンロード中… 50%").assertIsDisplayed()
        onNodeWithText("キャンセル").assertIsDisplayed().assertIsEnabled()
        // Downloading has no primary action button of its own — cancel is the only affordance.
        onAllNodesWithText("ダウンロード").assertCountEquals(0)
    }

    @Test
    fun clickingCancelInvokesTheCallback() = runDesktopComposeUiTest {
        var invoked = false
        val state = UpdateState.Downloading(installableUpdate(), bytesDone = 50, bytesTotal = 100)
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, { invoked = true }, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("キャンセル").performClick()

        assertEquals(true, invoked)
    }

    @Test
    fun verifyingShowsTheVerifyingMessage() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Verifying(installableUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("検証中…").assertIsDisplayed()
    }

    @Test
    fun readyShowsAnEnabledInstallButton() = runDesktopComposeUiTest {
        val state = UpdateState.Ready(installableUpdate(), filePath = "/tmp/x.zip")
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("再起動してインストール").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun readyShowsTheReadyToInstallWordingInsteadOfTheGenericAvailableHeadline() = runDesktopComposeUiTest {
        val state = UpdateState.Ready(installableUpdate(), filePath = "/tmp/x.zip")
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("2.0.0 の準備ができました").assertIsDisplayed()
        onAllNodesWithText("新しいバージョン 2.0.0 があります").assertCountEquals(0)
    }

    @Test
    fun theCardNoLongerShowsARedundantCurrentVersionLine() = runDesktopComposeUiTest {
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(UpdateState.Available(installableUpdate()), ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        // The current app version is already shown in the About dialog; showing it again here
        // just to the left of "there's a new version" added nothing.
        onAllNodesWithText("バージョン ${AppInfo.version}").assertCountEquals(0)
    }

    @Test
    fun clickingInstallInvokesTheCallback() = runDesktopComposeUiTest {
        var invoked = false
        val state = UpdateState.Ready(installableUpdate(), filePath = "/tmp/x.zip")
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, { invoked = true })
            }
        }
        waitForIdle()

        onNodeWithText("再起動してインストール").performClick()

        assertEquals(true, invoked)
    }

    @Test
    fun installingShowsADisabledButton() = runDesktopComposeUiTest {
        val state = UpdateState.Installing(installableUpdate())
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("再起動しています…").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun failedWithAnUpdateShowsTheErrorAndARetryButton() = runDesktopComposeUiTest {
        val state = UpdateState.Failed(installableUpdate(), UpdateException(UpdateStage.DOWNLOAD, "boom"))
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("アップデートに失敗しました").assertIsDisplayed()
        onNodeWithText("再試行").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun clickingRetryAfterADownloadFailureInvokesStartDownload() = runDesktopComposeUiTest {
        var invoked = false
        val state = UpdateState.Failed(installableUpdate(), UpdateException(UpdateStage.DOWNLOAD, "boom"))
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, { invoked = true }, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("再試行").performClick()

        assertEquals(true, invoked)
    }

    @Test
    fun failedWithNoKnownUpdateShowsTheCheckFailedMessage() = runDesktopComposeUiTest {
        val state = UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "boom"))
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, ::noop, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("確認できませんでした").assertIsDisplayed()
        onNodeWithText("再試行").assertIsDisplayed()
    }

    @Test
    fun clickingRetryAfterABareCheckFailureInvokesCheckForUpdate() = runDesktopComposeUiTest {
        var invoked = false
        val state = UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "boom"))
        setContent {
            Column(Modifier.width(360.dp)) {
                UpdateResultSection(state, { invoked = true }, ::noop, ::noop, ::noop)
            }
        }
        waitForIdle()

        onNodeWithText("再試行").performClick()

        assertEquals(true, invoked)
    }

    @Test
    fun theProgressSlotReservesTheSameHeightWhetherOrNotItHasContent() = runDesktopComposeUiTest {
        setContent {
            Column {
                Column(Modifier.testTag("available-section").width(360.dp)) {
                    UpdateResultSection(UpdateState.Available(installableUpdate()), ::noop, ::noop, ::noop, ::noop)
                }
                Column(Modifier.testTag("downloading-section").width(360.dp)) {
                    UpdateResultSection(
                        UpdateState.Downloading(installableUpdate(), 50, 100), ::noop, ::noop, ::noop, ::noop,
                    )
                }
            }
        }
        waitForIdle()

        // The slot itself carries the same UPDATE_PROGRESS_SLOT_TEST_TAG in both sections, so each
        // lookup is scoped to its own wrapping section's subtree (mirrors CloudProviderRowTest's
        // resettingDisablesResetActionWithoutChangingRowHeight, which does the same thing for a
        // spinner-vs-glyph swap inside a fixed slot).
        val emptySlotHeight = onNode(
            hasTestTag(UPDATE_PROGRESS_SLOT_TEST_TAG) and hasAnyAncestor(hasTestTag("available-section")),
        ).getBoundsInRoot().height
        val filledSlotHeight = onNode(
            hasTestTag(UPDATE_PROGRESS_SLOT_TEST_TAG) and hasAnyAncestor(hasTestTag("downloading-section")),
        ).getBoundsInRoot().height

        assertEquals(emptySlotHeight, filledSlotHeight)
    }

    @Test
    fun theStatusActionAreaReservesTheSameFloorHeightAcrossNoUpdateOutcomes() = runDesktopComposeUiTest {
        setContent {
            Column {
                Column(Modifier.testTag("idle-section").width(360.dp)) {
                    UpdateResultSection(UpdateState.Idle, ::noop, ::noop, ::noop, ::noop)
                }
                Column(Modifier.testTag("uptodate-section").width(360.dp)) {
                    UpdateResultSection(UpdateState.UpToDate, ::noop, ::noop, ::noop, ::noop)
                }
                Column(Modifier.testTag("failed-section").width(360.dp)) {
                    UpdateResultSection(
                        UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "boom")), ::noop, ::noop, ::noop, ::noop,
                    )
                }
            }
        }
        waitForIdle()

        // UpdateState.Idle renders nothing at all inside the reserved area, UpdateState.UpToDate a
        // single line of text, and this UpdateState.Failed a line of text plus a retry button — all
        // three must still measure the same floor height, or the interval control and "check for
        // update" button beneath the divider (not part of this composable, but positioned right
        // after it in UpdatesTabContent) would jump as the check resolves from one to another.
        fun heightOf(sectionTag: String) = onNode(
            hasTestTag(UPDATE_STATUS_ACTION_TEST_TAG) and hasAnyAncestor(hasTestTag(sectionTag)),
        ).getBoundsInRoot().height

        val idleHeight = heightOf("idle-section")
        val upToDateHeight = heightOf("uptodate-section")
        val failedHeight = heightOf("failed-section")

        assertEquals(idleHeight, upToDateHeight)
        assertEquals(idleHeight, failedHeight)
    }
}
