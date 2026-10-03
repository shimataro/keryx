package works.merc.keryx.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import works.merc.keryx.app.ui.common.KeryxDialogTab
import works.merc.keryx.app.ui.common.KeryxIcons

/**
 * [resolveSettingsEntry] decides where the settings dialog opens: on the requested tab when it is
 * rendered, otherwise on the default entry (`null` — Android's category list, desktop's first tab).
 */
class SettingsEntryTest {

    private val tabs = listOf(
        KeryxDialogTab("general", "General", KeryxIcons.Tune),
        KeryxDialogTab("cloud_sync", "Cloud Sync", KeryxIcons.Cloud),
        KeryxDialogTab("data", "Data", KeryxIcons.Storage),
    )

    @Test
    fun noRequestedTabOpensTheDefaultEntry() {
        assertNull(resolveSettingsEntry(null, tabs))
    }

    @Test
    fun aRenderedTabOpensOnThatTab() {
        assertEquals("cloud_sync", resolveSettingsEntry("cloud_sync", tabs))
        assertEquals("general", resolveSettingsEntry("general", tabs))
    }

    @Test
    fun anUnknownTabIdOpensTheDefaultEntry() {
        assertNull(resolveSettingsEntry("no_such_tab", tabs))
    }

    /** E.g. a `ShowSettingsTab("cloud_sync")` notification in a build with no cloud provider. */
    @Test
    fun aTabThatIsNotRenderedInThisBuildOpensTheDefaultEntry() {
        val withoutCloudSync = tabs.filterNot { it.id == "cloud_sync" }

        assertNull(resolveSettingsEntry("cloud_sync", withoutCloudSync))
    }
}
