package works.merc.keryx.app.tray

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.core.UpdateStage
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateAsset
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SOME_ASSET =
    UpdateAsset("Keryx-2.0.0-macos-arm64.zip", "https://x", 100L, "a".repeat(64), UpdateAssetKind.MAC_APP_ZIP)

private fun installableUpdate() =
    AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, SOME_ASSET, UpdatePlan.SelfReplace(SOME_ASSET))

private fun manualOnlyUpdate() =
    AvailableUpdate("2.0.0", "https://ex.com/2.0.0", null, null, UpdatePlan.OpenReleasePage)

/**
 * Full-coverage map of [updateMenuEntry] — the single entry both the system tray and the
 * application menu bar's Help menu show.
 *
 * Runs inside a real composition ([runDesktopComposeUiTest]) because the function resolves its
 * labels with `stringResource`; the build pins the test JVM's locale to Japanese (see
 * `composeApp/build.gradle.kts`), so the expected strings below are `values/strings.xml`'s.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateMenuEntryTest {

    private fun entryFor(state: UpdateState): TrayUpdateEntry {
        lateinit var entry: TrayUpdateEntry
        runDesktopComposeUiTest {
            setContent { entry = updateMenuEntry(state) }
            waitForIdle()
        }
        return entry
    }

    @Test
    fun idleOffersACheck() {
        val entry = entryFor(UpdateState.Idle)
        assertEquals("更新をチェック", entry.label)
        assertTrue(entry.enabled)
    }

    @Test
    fun checkingIsDisabledSoItCannotBeStartedTwice() {
        val entry = entryFor(UpdateState.Checking)
        assertEquals("更新を確認中…", entry.label)
        assertFalse(entry.enabled)
    }

    /** "Up to date" is still clickable: it is the way to ask for another check. */
    @Test
    fun upToDateStaysClickableAsARecheck() {
        val entry = entryFor(UpdateState.UpToDate)
        assertEquals("最新版です", entry.label)
        assertTrue(entry.enabled)
    }

    @Test
    fun anInstallableUpdateOffersItsDownloadByVersion() {
        val entry = entryFor(UpdateState.Available(installableUpdate()))
        assertEquals("アップデート 2.0.0 をダウンロード", entry.label)
        assertTrue(entry.enabled)
    }

    /**
     * A deb/rpm install (or any form the app can't apply itself) has no in-app download to offer,
     * but the entry stays enabled — clicking it opens the release page instead (see `main.kt`'s
     * `onUpdateMenuItemClicked`).
     */
    @Test
    fun aNonInstallableUpdateAnnouncesItselfWithoutPromisingADownload() {
        val entry = entryFor(UpdateState.Available(manualOnlyUpdate()))
        assertEquals("新しいバージョンがあります", entry.label)
        assertTrue(entry.enabled)
    }

    @Test
    fun downloadingShowsProgressRoundedToFivePercentAndIsDisabled() {
        val entry = entryFor(UpdateState.Downloading(installableUpdate(), bytesDone = 62, bytesTotal = 100))
        assertEquals("ダウンロード中… 60%", entry.label)
        assertFalse(entry.enabled)
    }

    @Test
    fun verifyingIsDisabled() {
        val entry = entryFor(UpdateState.Verifying(installableUpdate()))
        assertEquals("検証中…", entry.label)
        assertFalse(entry.enabled)
    }

    /**
     * Installing used to hide the entry entirely; now that the entry is always present it shows the
     * same wording the settings dialog's Updates tab uses for this state, disabled.
     */
    @Test
    fun installingShowsTheRestartingWordingAndIsDisabled() {
        val entry = entryFor(UpdateState.Installing(installableUpdate()))
        assertEquals("再起動しています…", entry.label)
        assertFalse(entry.enabled)
    }

    @Test
    fun readyOffersTheRestartByVersion() {
        val entry = entryFor(UpdateState.Ready(installableUpdate(), filePath = "/tmp/x.zip"))
        assertEquals("再起動して 2.0.0 にアップデート", entry.label)
        assertTrue(entry.enabled)
    }

    @Test
    fun failedStaysClickableAsARetry() {
        val entry = entryFor(
            UpdateState.Failed(installableUpdate(), UpdateException(UpdateStage.DOWNLOAD, "boom")),
        )
        assertEquals("アップデートに失敗しました", entry.label)
        assertTrue(entry.enabled)
    }

    /** A check that failed before anything was found carries no update, and must not crash. */
    @Test
    fun aCheckStageFailureWithNoUpdateStillProducesAnEntry() {
        val entry = entryFor(UpdateState.Failed(null, UpdateException(UpdateStage.CHECK, "no network")))
        assertEquals("アップデートに失敗しました", entry.label)
        assertTrue(entry.enabled)
    }
}
