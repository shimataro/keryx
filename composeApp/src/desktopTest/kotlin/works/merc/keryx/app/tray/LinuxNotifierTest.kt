package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.core.APP_NAME
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxNotifierTest {

    private class NotifyCall(
        val appName: String,
        val replacesId: UInt32,
        val appIcon: String,
        val summary: String,
        val body: String,
        val actions: List<String>,
        val hints: Map<String, Variant<*>>,
        val expireTimeout: Int,
    )

    /** Records every [Notify] and answers with [nextId], or throws [failure] when set. */
    private class FakeNotifications : FreedesktopNotifications {
        val calls = mutableListOf<NotifyCall>()
        var nextId = UInt32(42)
        var failure: RuntimeException? = null

        override fun Notify(
            appName: String,
            replacesId: UInt32,
            appIcon: String,
            summary: String,
            body: String,
            actions: List<String>,
            hints: Map<String, Variant<*>>,
            expireTimeout: Int,
        ): UInt32 {
            failure?.let { throw it }
            calls += NotifyCall(appName, replacesId, appIcon, summary, body, actions, hints, expireTimeout)
            return nextId
        }

        override fun isRemote(): Boolean = false

        override fun getObjectPath(): String = "/org/freedesktop/Notifications"
    }

    private val icon = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

    @Test
    fun `with an icon the hints carry the image data`() {
        val daemon = FakeNotifications()
        LinuxNotifier({ daemon }, icon).notify("Summary", "Body")

        val hints = daemon.calls.single().hints
        assertEquals(setOf("image-data"), hints.keys)
        val variant = hints.getValue("image-data")
        assertEquals("(iiibiiay)", variant.sig)
        val data = assertIs<NotificationImageData>(variant.value)
        assertEquals(48, data.width)
        assertEquals(48, data.height)
    }

    @Test
    fun `without an icon the hints are empty`() {
        val daemon = FakeNotifications()
        LinuxNotifier({ daemon }, null).notify("Summary", "Body")

        assertTrue(daemon.calls.single().hints.isEmpty())
    }

    @Test
    fun `the fixed arguments and the text are passed through`() {
        val daemon = FakeNotifications()
        LinuxNotifier({ daemon }, null).notify("3 new articles", "Feed A and more")

        val call = daemon.calls.single()
        assertEquals(APP_NAME, call.appName)
        assertEquals(UInt32(0), call.replacesId)
        assertEquals("", call.appIcon)
        assertEquals(listOf("default", ""), call.actions)
        assertEquals(-1, call.expireTimeout)
        assertEquals("3 new articles", call.summary)
        assertEquals("Feed A and more", call.body)
    }

    @Test
    fun `the returned id is recorded and consumed exactly once`() {
        val daemon = FakeNotifications().apply { nextId = UInt32(7) }
        val notifier = LinuxNotifier({ daemon }, null)
        notifier.notify("s", "b")

        assertFalse(notifier.consumeIfOwn(UInt32(8)))
        assertTrue(notifier.consumeIfOwn(UInt32(7)))
        assertFalse(notifier.consumeIfOwn(UInt32(7)))
    }

    @Test
    fun `a throwing Notify records nothing`() {
        val daemon = FakeNotifications().apply { failure = IllegalStateException("daemon gone") }
        val notifier = LinuxNotifier({ daemon }, null)
        notifier.notify("s", "b")

        assertTrue(daemon.calls.isEmpty())
        assertFalse(notifier.consumeIfOwn(daemon.nextId))
    }

    @Test
    fun `a missing daemon makes notify a no-op`() {
        val notifier = LinuxNotifier({ null }, icon)

        notifier.notify("s", "b")

        assertFalse(notifier.consumeIfOwn(UInt32(42)))
    }

    @Test
    fun `the daemon is looked up on each notify`() {
        val daemon = FakeNotifications()
        var available: FreedesktopNotifications? = null
        val notifier = LinuxNotifier({ available }, null)

        notifier.notify("first", "b")
        available = daemon
        notifier.notify("second", "b")

        assertEquals(listOf("second"), daemon.calls.map { it.summary })
    }
}
