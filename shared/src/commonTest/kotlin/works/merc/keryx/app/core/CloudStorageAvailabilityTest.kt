package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudStorageAvailabilityTest {

    @Test
    fun filtersOutUnavailableBackends() {
        // Android on a device without Play services (a de-Googled ROM): Dropbox and OneDrive are
        // configured, but Google Drive has no authorization path there at all — see
        // CloudStorageAvailability.android.kt. Nextcloud needs nothing from the device or the build.
        assertEquals(
            listOf(CloudStorageType.DROPBOX, CloudStorageType.ONEDRIVE, CloudStorageType.NEXTCLOUD),
            availableCloudStorageTypes(dropbox = true, googleDrive = false, oneDrive = true, nextcloud = true),
        )
    }

    @Test
    fun listsTheOAuthBackendsWithGoogleDriveLastAndNextcloudAfterThem() {
        // The UI display order is CloudStorageType's own declaration order. Pin it with an explicit
        // list (comparing against `entries` would follow any reordering of the enum): Google Drive,
        // the only backend hidden on some builds/devices, comes after the other OAuth backends so
        // hiding it never shifts them; Nextcloud, the self-hosted provider, comes last of all.
        assertEquals(
            listOf(CloudStorageType.DROPBOX, CloudStorageType.ONEDRIVE, CloudStorageType.GOOGLE_DRIVE, CloudStorageType.NEXTCLOUD),
            availableCloudStorageTypes(dropbox = true, googleDrive = true, oneDrive = true, nextcloud = true),
        )
    }

    @Test
    fun anUnconfiguredBuildStillOffersNextcloud() {
        // The F-Droid build (and any build without OAuth keys): Nextcloud needs no build-time key.
        assertEquals(
            listOf(CloudStorageType.NEXTCLOUD),
            availableCloudStorageTypes(dropbox = false, googleDrive = false, oneDrive = false, nextcloud = CloudStorageAvailability.nextcloudAvailable),
        )
    }

    @Test
    fun emptyWhenNothingIsConfigured() {
        val result = availableCloudStorageTypes(dropbox = false, googleDrive = false, oneDrive = false, nextcloud = false)
        assertTrue(result.isEmpty())
    }

    @Test
    fun onlyNextcloudAsksForAServerAddress() {
        assertEquals(listOf(CloudStorageType.NEXTCLOUD), CloudStorageType.entries.filter { it.requiresServerUrl })
        assertFalse(CloudStorageType.DROPBOX.requiresServerUrl)
        assertEquals(CloudStorageType.NEXTCLOUD, CloudStorageType.fromId("nextcloud"))
    }
}
