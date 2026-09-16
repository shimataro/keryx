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
    fun preservesCloudStorageTypeDeclarationOrderWhenAllAvailable() {
        // The UI display order is CloudStorageType's own declaration order — pin it so a future
        // reordering of the enum (or of this filter) is caught here rather than only visually.
        assertEquals(
            CloudStorageType.entries,
            availableCloudStorageTypes(dropbox = true, googleDrive = true, oneDrive = true),
        )
    }

    @Test
    fun emptyWhenNothingIsConfigured() {
        val result = availableCloudStorageTypes(dropbox = false, googleDrive = false, oneDrive = false)
        assertTrue(result.isEmpty())
    }
}
