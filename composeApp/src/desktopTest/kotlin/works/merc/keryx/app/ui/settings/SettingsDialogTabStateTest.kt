package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import works.merc.keryx.app.ui.common.KeryxDialogTab
import works.merc.keryx.app.ui.common.KeryxIcons

@OptIn(ExperimentalTestApi::class)
class SettingsDialogTabStateTest {

    private val tabsWithCloudSync = listOf(
        KeryxDialogTab("general", "General", KeryxIcons.Tune),
        KeryxDialogTab("cloud_sync", "Cloud Sync", KeryxIcons.Cloud),
    )

    private val tabsWithoutCloudSync = listOf(
        KeryxDialogTab("general", "General", KeryxIcons.Tune),
        KeryxDialogTab("data", "Data", KeryxIcons.Storage),
    )

    @Test
    fun initializesToInitialTabId() = runDesktopComposeUiTest {
        lateinit var selectedTabIdState: MutableState<String>

        setContent {
            selectedTabIdState = rememberSelectedTabId(
                initialTabId = "cloud_sync",
                tabRequestToken = 0,
                tabs = tabsWithCloudSync,
            )
        }
        waitForIdle()

        assertEquals("cloud_sync", selectedTabIdState.value)
    }

    @Test
    fun manualTabSwitchChangesValue() = runDesktopComposeUiTest {
        lateinit var selectedTabIdState: MutableState<String>

        setContent {
            selectedTabIdState = rememberSelectedTabId(
                initialTabId = "cloud_sync",
                tabRequestToken = 0,
                tabs = tabsWithCloudSync,
            )
        }
        waitForIdle()

        selectedTabIdState.value = "general"
        waitForIdle()

        assertEquals("general", selectedTabIdState.value)
    }

    @Test
    fun requestTokenBumpReNavigatesEvenWithSameTabId() = runDesktopComposeUiTest {
        var initialTabId by mutableStateOf("cloud_sync")
        var tabRequestToken by mutableStateOf(0)
        lateinit var selectedTabIdState: MutableState<String>

        setContent {
            selectedTabIdState = rememberSelectedTabId(initialTabId, tabRequestToken, tabsWithCloudSync)
        }
        waitForIdle()
        assertEquals("cloud_sync", selectedTabIdState.value)

        // The user manually switches away from the tab the dialog opened on.
        selectedTabIdState.value = "general"
        waitForIdle()
        assertEquals("general", selectedTabIdState.value)

        // A fresh request re-targets the same tab id the dialog already had open. Without the
        // request token, remember(initialTabId) would see an unchanged key and stay on "general".
        tabRequestToken++
        waitForIdle()

        assertEquals("cloud_sync", selectedTabIdState.value)
    }

    @Test
    fun fallsBackToFirstTabWhenInitialTabIdIsNotInTabs() = runDesktopComposeUiTest {
        lateinit var selectedTabIdState: MutableState<String>

        setContent {
            selectedTabIdState = rememberSelectedTabId(
                initialTabId = "cloud_sync",
                tabRequestToken = 0,
                tabs = tabsWithoutCloudSync,
            )
        }
        waitForIdle()

        assertEquals("general", selectedTabIdState.value)
    }

    @Test
    fun requestTokenBumpFallsBackWhenTabBecameUnavailable() = runDesktopComposeUiTest {
        var tabRequestToken by mutableStateOf(0)
        lateinit var selectedTabIdState: MutableState<String>

        setContent {
            selectedTabIdState = rememberSelectedTabId(
                initialTabId = "cloud_sync",
                tabRequestToken = tabRequestToken,
                tabs = tabsWithoutCloudSync,
            )
        }
        waitForIdle()
        assertEquals("general", selectedTabIdState.value)

        selectedTabIdState.value = "data"
        waitForIdle()
        assertEquals("data", selectedTabIdState.value)

        // A fresh request re-targets "cloud_sync" again, but it's still unavailable.
        tabRequestToken++
        waitForIdle()

        assertEquals("general", selectedTabIdState.value)
    }

    /** Stands in for an Android configuration change: the composition is torn down after its
     *  saveable state was saved, then rebuilt from that saved state. */
    @Test
    fun manualTabSwitchSurvivesStateRestoration() = runDesktopComposeUiTest {
        lateinit var selectedTabIdState: MutableState<String>
        var registry by mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        var shown by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (shown) {
                    selectedTabIdState = rememberSelectedTabId(
                        initialTabId = "general",
                        tabRequestToken = 0,
                        tabs = tabsWithCloudSync,
                    )
                }
            }
        }
        waitForIdle()
        selectedTabIdState.value = "cloud_sync"
        waitForIdle()

        val saved = registry.performSave()
        shown = false
        waitForIdle()
        registry = SaveableStateRegistry(restoredValues = saved) { true }
        shown = true
        waitForIdle()

        assertEquals("cloud_sync", selectedTabIdState.value)
    }
}
