package works.merc.keryx.app.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import works.merc.keryx.app.core.CloudStorageType

class ServerDialogRequestSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun roundTrip(request: ServerDialogRequest?): ServerDialogRequest? {
        val saved = with(ServerDialogRequestSaver) { scope.save(request) } ?: return null
        return ServerDialogRequestSaver.restore(saved)
    }

    @Test
    fun everyKindRoundTripsWithItsProviderAndUrl() {
        ServerDialogKind.entries.forEach { kind ->
            val request = ServerDialogRequest(CloudStorageType.NEXTCLOUD, kind, "https://cloud.example.com")
            assertEquals(request, roundTrip(request))
        }
    }

    @Test
    fun anEmptyInitialUrlRoundTrips() {
        val request = ServerDialogRequest(CloudStorageType.NEXTCLOUD, ServerDialogKind.CONNECT, "")
        assertEquals(request, roundTrip(request))
    }

    @Test
    fun noOpenDialogRestoresAsNone() {
        assertNull(roundTrip(null))
    }

    @Test
    fun anUnknownProviderOrKindRestoresAsNone() {
        assertNull(ServerDialogRequestSaver.restore(listOf("unknown", "CONNECT", "")))
        assertNull(ServerDialogRequestSaver.restore(listOf("nextcloud", "UNKNOWN", "")))
    }
}
