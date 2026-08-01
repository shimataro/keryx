package works.merc.keryx.app.data.local

import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSettingsStoreTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "settings-test-${Random.nextInt()}")
    private val store = LocalSettingsStore(dirOverride = dir)

    @AfterTest
    fun cleanup() {
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    @Test
    fun defaultsWhenNoFile() {
        assertFalse(store.isSetupComplete())
        val s = store.load()
        assertEquals("system", s.themeMode)
        assertEquals(30, s.refreshIntervalMinutes)
    }

    @Test
    fun savesAndLoadsRoundTrip() {
        store.save(LocalSettings(themeMode = "dark", refreshIntervalMinutes = 60, cloudStorageType = "dropbox"))
        assertTrue(store.isSetupComplete())
        val s = store.load()
        assertEquals("dark", s.themeMode)
        assertEquals(60, s.refreshIntervalMinutes)
        assertEquals("dropbox", s.cloudStorageType)
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        FileIO.writeText(FileIO.join(dir, "local_settings.json"), "{ not valid json")
        assertEquals("system", store.load().themeMode)
    }

    @Test
    fun collapsedFolderIdsDefaultsToEmptySet() {
        assertEquals(emptySet(), store.load().collapsedFolderIds)
    }

    @Test
    fun collapsedFolderIdsRoundTrips() {
        store.save(LocalSettings(collapsedFolderIds = setOf("d1", "d2")))
        assertEquals(setOf("d1", "d2"), store.load().collapsedFolderIds)
    }

    @Test
    fun expandedTagIdsDefaultsToEmptySet() {
        assertEquals(emptySet(), store.load().expandedTagIds)
    }

    @Test
    fun expandedTagIdsRoundTrips() {
        store.save(LocalSettings(expandedTagIds = setOf("t1", "t2")))
        assertEquals(setOf("t1", "t2"), store.load().expandedTagIds)
    }

    @Test
    fun loadingOldSettingsFileWithoutExpandedTagIdsKeyStillWorks() {
        // Simulates a `local_settings.json` written before `expandedTagIds` existed.
        FileIO.writeText(
            FileIO.join(dir, "local_settings.json"),
            """{"themeMode":"dark","collapsedFolderIds":["d1"]}""",
        )

        val s = store.load()

        assertEquals("dark", s.themeMode)
        assertEquals(setOf("d1"), s.collapsedFolderIds)
        assertEquals(emptySet(), s.expandedTagIds)
    }

    @Test
    fun lastFtsRebuiltAtRoundTrips() {
        assertEquals(null, store.load().lastFtsRebuiltAt)
        store.save(LocalSettings(lastFtsRebuiltAt = 1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, store.load().lastFtsRebuiltAt)
    }

    @Test
    fun loadingOldSettingsFileWithoutCollapsedFolderIdsKeyStillWorks() {
        // Simulates a `local_settings.json` written before `collapsedFolderIds` existed.
        FileIO.writeText(
            FileIO.join(dir, "local_settings.json"),
            """{"themeMode":"dark","fontSizeScale":1.0,"refreshIntervalMinutes":30,"startMinimized":false,"cloudStorageType":null,"notificationEnabled":true,"lastCacheCleanupAt":null,"windowWidth":null,"windowHeight":null,"feedListPaneWidth":260.0,"articleListPaneWidth":360.0}""",
        )

        val s = store.load()

        assertEquals("dark", s.themeMode)
        assertEquals(emptySet(), s.collapsedFolderIds)
    }
}
