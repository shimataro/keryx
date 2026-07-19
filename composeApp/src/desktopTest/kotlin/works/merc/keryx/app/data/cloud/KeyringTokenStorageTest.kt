package works.merc.keryx.app.data.cloud

import com.github.javakeyring.PasswordAccessException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyringTokenStorageTest {
    @Test
    fun notFoundIsTreatedAsExpectedAndNotWarned() {
        // macOS wording
        assertTrue(
            isExpectedKeyringLoadFailure(
                PasswordAccessException("No stored credentials match works.merc.keryx account: dropbox"),
            ),
        )
        // Windows wording — same type, still expected (message matching would miss this)
        assertTrue(isExpectedKeyringLoadFailure(PasswordAccessException("Password not Found")))
    }

    @Test
    fun unexpectedThrowableIsNotExpected() {
        assertFalse(isExpectedKeyringLoadFailure(RuntimeException("driver blew up")))
        assertFalse(isExpectedKeyringLoadFailure(IllegalStateException()))
    }
}
