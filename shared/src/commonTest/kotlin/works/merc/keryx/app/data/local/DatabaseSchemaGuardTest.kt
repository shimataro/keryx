package works.merc.keryx.app.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DatabaseSchemaGuardTest {

    @Test
    fun newerDatabaseVersionIsRejected() {
        val e = assertFailsWith<DatabaseTooNewException> { requireSupportedSchemaVersion(3, 2) }
        assertEquals(3, e.databaseVersion)
        assertEquals(2, e.supportedVersion)
    }

    @Test
    fun equalOlderAndFreshVersionsAreAccepted() {
        requireSupportedSchemaVersion(2, 2)
        requireSupportedSchemaVersion(1, 2)
        requireSupportedSchemaVersion(0, 2)
    }

    @Test
    fun findsTheExceptionThroughWrappingCauses() {
        val tooNew = DatabaseTooNewException(3, 2)
        val wrapped = RuntimeException("instance creation failed", IllegalStateException("inner", tooNew))
        assertSame(tooNew, findDatabaseTooNew(wrapped))
        assertNull(findDatabaseTooNew(RuntimeException("unrelated")))
    }
}
