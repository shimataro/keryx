package works.merc.keryx.app.appmenu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import works.merc.keryx.app.tray.DBusMenuEventEntry
import works.merc.keryx.app.ui.menu.AppMenuNode
import works.merc.keryx.app.ui.menu.AppMenuRoot
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the parts of [AppMenuDBusMenu] that need no bus: revision bookkeeping, `AboutToShow`
 * staleness, and event dispatch. Possible because the object takes an `onLayoutUpdated` callback
 * and publishes clicks on [AppMenuDBusMenu.clickedIds] rather than holding a `DBusConnection`.
 *
 * Ids in the fixture (pre-order): root=0, File=1, Add=2, Separator=3, Quit=4, View=5, Unread=6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppMenuRevisionTest {

    private val emitted = mutableListOf<Int>()

    private fun root(addLabel: String = "Add") = AppMenuRoot(
        listOf(
            AppMenuNode.Menu(
                "File",
                listOf(
                    AppMenuNode.Item(addLabel, enabled = true, onClick = {}),
                    AppMenuNode.Separator,
                    AppMenuNode.Item("Quit", enabled = false, onClick = {}),
                ),
            ),
            AppMenuNode.Menu(
                "View",
                listOf(AppMenuNode.CheckboxItem("Unread", enabled = true, checked = true, onCheckedChange = {})),
            ),
        ),
    )

    private fun menu() = AppMenuDBusMenu(objectPath = "/com/canonical/menu/keryx", onLayoutUpdated = { emitted.add(it) })

    /** Populates the menu with the fixture tree so ids 2/4/6 exist. */
    private fun populatedMenu() = menu().also { it.updateState(root()) }

    private fun clicked(id: Int) = DBusMenuEventEntry(id, "clicked", Variant(""), UInt32(0))

    @Test
    fun `every updateState bumps the revision and emits, with no equality dedup`() {
        val menu = menu()
        val before = menu.currentRevision

        menu.updateState(root())
        menu.updateState(root()) // identical shape/labels: still bumps (lambdas never compare equal)

        assertEquals(listOf(before + 1, before + 2), emitted)
        assertEquals(before + 2, menu.currentRevision)
    }

    @Test
    fun `the layout reply carries the current revision`() {
        val menu = populatedMenu()
        val reply = menu.GetLayout(APPMENU_ROOT_ID, -1, emptyList())
        assertEquals(menu.currentRevision, reply.revision.toInt())
    }

    @Test
    fun `aboutToShow reports an update until the host refetches the layout`() {
        val menu = populatedMenu()
        assertTrue(menu.AboutToShow(APPMENU_ROOT_ID), "nothing served yet")

        menu.GetLayout(APPMENU_ROOT_ID, -1, emptyList())
        assertFalse(menu.AboutToShow(APPMENU_ROOT_ID))

        menu.updateState(root("追加"))
        assertTrue(menu.AboutToShow(APPMENU_ROOT_ID), "a rebuild makes the served layout stale")

        menu.GetLayout(APPMENU_ROOT_ID, -1, emptyList())
        assertFalse(menu.AboutToShow(APPMENU_ROOT_ID), "refetching clears the staleness")
    }

    @Test
    fun `aboutToShowGroup reports known stale ids and flags unknown ones`() {
        val menu = populatedMenu()
        menu.GetLayout(APPMENU_ROOT_ID, -1, emptyList())
        menu.updateState(root("追加"))

        val reply = menu.AboutToShowGroup(listOf(APPMENU_ROOT_ID, 2, 999))
        assertEquals(listOf(APPMENU_ROOT_ID, 2), reply.updatesNeeded)
        assertEquals(listOf(999), reply.idErrors)
    }

    @Test
    fun `clicking an actionable item publishes its id`() = runTest {
        val menu = populatedMenu()
        val clicks = collect(menu.clickedIds)
        runCurrent()

        menu.Event(2, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertEquals(listOf(2), clicks)
    }

    @Test
    fun `a non-click event on a known item publishes nothing`() = runTest {
        val menu = populatedMenu()
        val clicks = collect(menu.clickedIds)
        runCurrent()

        menu.Event(2, "hovered", Variant(""), UInt32(0))
        menu.Event(2, "opened", Variant(""), UInt32(0))
        runCurrent()

        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `clicking a separator or submenu node publishes nothing but is still accepted`() = runTest {
        val menu = populatedMenu()
        val clicks = collect(menu.clickedIds)
        runCurrent()

        // ids 1 (submenu) and 3 (separator) are known but not actionable.
        val unhandled = menu.EventGroup(listOf(clicked(1), clicked(3)))
        runCurrent()

        assertTrue(unhandled.isEmpty(), "known ids are accepted even if not actionable")
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `an event on an unknown id is ignored`() = runTest {
        val menu = populatedMenu()
        val clicks = collect(menu.clickedIds)
        runCurrent()

        menu.Event(999, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertTrue(clicks.isEmpty())
    }

    @Test
    fun `eventGroup returns only the ids it could not handle`() {
        val menu = populatedMenu()
        val unhandled = menu.EventGroup(listOf(clicked(2), clicked(999), clicked(4)))
        assertEquals(listOf(999), unhandled)
    }

    @Test
    fun `a layout reply never disagrees with its revision under concurrency`() {
        val states = listOf(root("A"), root("B"))
        val menu = AppMenuDBusMenu("/com/canonical/menu/keryx", onLayoutUpdated = {})
        // Menu starts at revision 1 with an empty tree; the first write installs states[0] at
        // revision 2, so revision R (>=2) describes states[(R - 2) % 2] = states[R % 2].
        val mismatches = ConcurrentLinkedQueue<String>()

        val writer = thread { repeat(20_000) { i -> menu.updateState(states[i % 2]) } }
        repeat(20_000) {
            val reply = menu.GetLayout(2, 0, emptyList())
            val revision = reply.revision.toInt()
            if (revision >= 2) {
                val expected = if (revision % 2 == 0) "A" else "B"
                val actual = reply.layout.properties["label"]?.value
                if (expected != actual) mismatches += "revision $revision served '$actual', expected '$expected'"
            }
        }
        writer.join()

        assertTrue(mismatches.isEmpty(), mismatches.firstOrNull().orEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.collect(flow: SharedFlow<Int>): List<Int> {
        val received = mutableListOf<Int>()
        backgroundScope.launch { flow.collect { received.add(it) } }
        return received
    }
}
