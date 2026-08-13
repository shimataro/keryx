package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingNotificationIdsTest {
    @Test
    fun `consume returns true and forgets an id that was added`() {
        val ids = PendingNotificationIds()
        val id = UInt32(1)

        ids.add(id)

        assertTrue(ids.consume(id))
        assertFalse(ids.consume(id), "a second consume of the same id must not find it again")
    }

    @Test
    fun `consume returns false for an id that was never added`() {
        val ids = PendingNotificationIds()

        assertFalse(ids.consume(UInt32(1)))
    }

    @Test
    fun `adding the same id twice still consumes cleanly once`() {
        val ids = PendingNotificationIds()
        val id = UInt32(1)

        ids.add(id)
        ids.add(id)

        assertTrue(ids.consume(id))
        assertFalse(ids.consume(id))
    }
}
