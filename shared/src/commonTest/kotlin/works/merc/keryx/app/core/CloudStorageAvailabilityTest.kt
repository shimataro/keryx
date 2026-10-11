package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudStorageAvailabilityTest {

    @Test
    fun filtersOutUnavailableBackends() {
        // Android on a device without Play services (a de-Googled ROM): Dropbox and OneDrive are
        // configured, but Google Drive has no authorization path there at all — see
        // CloudStorageAvailability.android.kt.
        assertEquals(
            listOf(CloudStorageType.DROPBOX, CloudStorageType.ONEDRIVE),
            availableCloudStorageTypes(dropbox = true, googleDrive = false, oneDrive = true),
        )
    }

    @Test
    fun listsAlwaysOfferedBackendsBeforeGoogleDriveWhenAllAvailable() {
        // The UI display order is CloudStorageType's own declaration order. Pin it with an explicit
        // list (comparing against `entries` would follow any reordering of the enum): Google Drive,
        // the only backend hidden on some builds/devices, comes last so hiding it never shifts the
        // others.
        assertEquals(
            listOf(CloudStorageType.DROPBOX, CloudStorageType.ONEDRIVE, CloudStorageType.GOOGLE_DRIVE),
            availableCloudStorageTypes(dropbox = true, googleDrive = true, oneDrive = true),
        )
    }

    @Test
    fun emptyWhenNothingIsConfigured() {
        val result = availableCloudStorageTypes(dropbox = false, googleDrive = false, oneDrive = false)
        assertTrue(result.isEmpty())
    }
}
