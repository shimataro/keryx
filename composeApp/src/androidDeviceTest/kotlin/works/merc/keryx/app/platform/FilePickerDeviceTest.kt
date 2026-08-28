package works.merc.keryx.app.platform

import android.net.Uri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * See review finding #4 (`v0.11.0..HEAD`): [ContentUriPickedFile.writeText] used to swallow every
 * write failure into a logged warning, so a failed OPML export still reported success to the user
 * (`SettingsViewModel.exportOpml` only distinguishes success from failure by whether `writeText`
 * threw). This exercises the actual failure path a real device hits — no content provider is
 * registered for this bogus authority, so `ContentResolver.openOutputStream` cannot open a stream —
 * rather than mocking `ContentResolver`, since the SAF-picked `Uri`s this class is built for are
 * themselves opaque `content://` handles no test can construct any more legitimately than this.
 */
class FilePickerDeviceTest {

    @Test
    fun writeTextThrowsWhenNoProviderCanOpenTheStream() = runTest {
        val uri = Uri.parse("content://works.merc.keryx.app.filepickerdevicetest.nonexistent/nope")
        val pickedFile = ContentUriPickedFile(uri)

        assertFailsWith<Exception> { pickedFile.writeText("<opml/>") }
    }
}
