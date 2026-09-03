package works.merc.keryx.app.tray

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the parts of [SniDBusMenu] that need no bus: revision bookkeeping, the
 * `AboutToShow` staleness answer, and event dispatch. Possible because the object takes an
 * `onLayoutUpdated` callback instead of holding a `DBusConnection`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrayMenuRevisionTest {
    private val updateEntry = TrayUpdateEntry("更新をチェック", enabled = true)
    private val hidden = TrayMenuState(toggleLabel = "表示", quitLabel = "終了", update = updateEntry)
    private val shown = TrayMenuState(toggleLabel = "非表示", quitLabel = "終了", update = updateEntry)

    private val emitted = mutableListOf<Int>()

    private fun menu(initial: TrayMenuState = hidden) = SniDBusMenu(
        objectPath = SniConnection.MENU_PATH,
        initialState = initial,
        onLayoutUpdated = { emitted.add(it) },
    )

    private fun SniDBusMenu.fetchLayout() = GetLayout(MENU_ROOT_ID, -1, emptyList())

    /** The toggle item out of a root layout reply — looked up by id rather than by position, since
     * the update entry and its separator always sit ahead of it. */
    private fun MenuLayoutReply.toggleItem(): DBusMenuLayoutItem =
        layout.children.map { it.value as DBusMenuLayoutItem }.first { it.id == MENU_TOGGLE_ID }

    private fun clicked(id: Int) = DBusMenuEventEntry(id, "clicked", Variant(""), UInt32(0))

    @Test
    fun `changing the labels bumps the revision and emits LayoutUpdated once`() {
        val menu = menu()
        val before = menu.currentRevision

        menu.updateState(shown)

        assertEquals(listOf(before + 1), emitted)
        assertEquals(before + 1, menu.currentRevision)
    }

    @Test
    fun `re-publishing the same labels neither bumps the revision nor emits`() {
        val menu = menu()
        val before = menu.currentRevision

        menu.updateState(hidden)
        menu.updateState(hidden)

        assertTrue(emitted.isEmpty())
        assertEquals(before, menu.currentRevision)
    }

    @Test
    fun `aboutToShow reports an update until the host refetches the layout`() {
        val menu = menu()
        // Nothing served yet, so the host's (absent) copy is stale by definition.
        assertTrue(menu.AboutToShow(MENU_ROOT_ID))

        menu.fetchLayout()
        assertFalse(menu.AboutToShow(MENU_ROOT_ID))

        menu.updateState(shown)
        assertTrue(menu.AboutToShow(MENU_ROOT_ID), "a label change makes the served layout stale")

        menu.fetchLayout()
        assertFalse(menu.AboutToShow(MENU_ROOT_ID), "refetching clears the staleness")
    }

    @Test
    fun `the layout reply carries the current revision and labels`() {
        val menu = menu()
        menu.updateState(shown)

        val reply = menu.fetchLayout()

        assertEquals(menu.currentRevision, reply.revision.toInt())
        assertEquals("非表示", reply.toggleItem().properties.getValue("label").value)
    }

    @Test
    fun `a layout reply never stamps one revision's labels with another revision`() {
        // Built locally with a no-op callback: the shared `emitted` list is not thread-safe.
        val states = listOf(hidden, shown)
        val menu = SniDBusMenu(SniConnection.MENU_PATH, states[0], onLayoutUpdated = {})
        val mismatches = ConcurrentLinkedQueue<String>()

        // The menu starts at revision 1 with states[0] and the writer's first call passes
        // states[1], so revision R always describes states[(R - 1) % 2].
        val writer = thread { repeat(20_000) { i -> menu.updateState(states[(i + 1) % 2]) } }
        repeat(20_000) {
            val reply = menu.fetchLayout()
            val revision = reply.revision.toInt()
            val expected = states[(revision - 1) % 2].toggleLabel
            val actual = reply.toggleItem().properties.getValue("label").value
            if (expected != actual) {
                mismatches += "revision $revision served '$actual', expected '$expected'"
            }
        }
        writer.join()

        assertTrue(mismatches.isEmpty(), mismatches.firstOrNull().orEmpty())
    }

    @Test
    fun `aboutToShowGroup reports known stale ids and flags unknown ones`() {
        val menu = menu()
        menu.fetchLayout()
        menu.updateState(shown)

        val reply = menu.AboutToShowGroup(listOf(MENU_ROOT_ID, MENU_TOGGLE_ID, 99))

        assertEquals(listOf(MENU_ROOT_ID, MENU_TOGGLE_ID), reply.updatesNeeded)
        assertEquals(listOf(99), reply.idErrors)
    }

    @Test
    fun `clicking the toggle item emits a toggle request`() = runTest {
        val menu = menu()
        val toggles = collect(menu.toggleRequests)
        val quits = collect(menu.quitRequests)
        runCurrent()

        menu.Event(MENU_TOGGLE_ID, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertEquals(1, toggles.size)
        assertTrue(quits.isEmpty())
    }

    @Test
    fun `clicking the quit item emits a quit request`() = runTest {
        val menu = menu()
        val toggles = collect(menu.toggleRequests)
        val quits = collect(menu.quitRequests)
        runCurrent()

        menu.Event(MENU_QUIT_ID, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertEquals(1, quits.size)
        assertTrue(toggles.isEmpty())
    }

    @Test
    fun `non-click events on a known item do nothing`() = runTest {
        val menu = menu()
        val toggles = collect(menu.toggleRequests)
        runCurrent()

        menu.Event(MENU_TOGGLE_ID, "hovered", Variant(""), UInt32(0))
        menu.Event(MENU_TOGGLE_ID, "opened", Variant(""), UInt32(0))
        menu.Event(MENU_TOGGLE_ID, "closed", Variant(""), UInt32(0))
        runCurrent()

        assertTrue(toggles.isEmpty())
    }

    @Test
    fun `an event on an unknown id is ignored`() = runTest {
        val menu = menu()
        val toggles = collect(menu.toggleRequests)
        val quits = collect(menu.quitRequests)
        runCurrent()

        menu.Event(99, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertTrue(toggles.isEmpty())
        assertTrue(quits.isEmpty())
    }

    @Test
    fun `eventGroup returns only the ids it could not handle`() {
        val menu = menu()
        val unhandled = menu.EventGroup(listOf(clicked(MENU_TOGGLE_ID), clicked(99), clicked(MENU_QUIT_ID)))
        assertEquals(listOf(99), unhandled)
    }

    private fun kotlinx.coroutines.test.TestScope.collect(flow: SharedFlow<Unit>): List<Unit> {
        val received = mutableListOf<Unit>()
        backgroundScope.launch { flow.collect { received.add(it) } }
        return received
    }

    // --- update entry ---

    @Test
    fun `clicking an enabled update item emits an update request`() = runTest {
        val menu = menu(hidden.copy(update = TrayUpdateEntry("Download update 2.0.0", enabled = true)))
        val updates = collect(menu.updateRequests)
        runCurrent()

        menu.Event(MENU_UPDATE_ID, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertEquals(1, updates.size)
    }

    @Test
    fun `clicking a disabled update item is silently ignored`() = runTest {
        val menu = menu(hidden.copy(update = TrayUpdateEntry("Downloading… 60%", enabled = false)))
        val updates = collect(menu.updateRequests)
        runCurrent()

        menu.Event(MENU_UPDATE_ID, "clicked", Variant(""), UInt32(0))
        runCurrent()

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `aboutToShowGroup recognizes the update and separator ids`() {
        val menu = menu(hidden)
        menu.fetchLayout()

        val reply = menu.AboutToShowGroup(listOf(MENU_UPDATE_ID, MENU_SEPARATOR_ID, 99))

        assertEquals(emptyList(), reply.updatesNeeded) // nothing stale yet
        assertEquals(listOf(99), reply.idErrors) // only the genuinely unknown id is flagged
    }

    @Test
    fun `the layout reply always includes the update entry and its separator`() {
        val menu = menu()
        menu.updateState(shown.copy(update = TrayUpdateEntry("Download update 2.0.0", enabled = true)))

        val reply = menu.fetchLayout()

        val childIds = reply.layout.children.map { (it.value as DBusMenuLayoutItem).id }
        assertEquals(listOf(MENU_UPDATE_ID, MENU_SEPARATOR_ID, MENU_TOGGLE_ID, MENU_QUIT_ID), childIds)
    }

    /** Only the update entry changing is still a layout change the host must be told about. */
    @Test
    fun `changing only the update entry bumps the revision and emits LayoutUpdated`() {
        val menu = menu()
        val before = menu.currentRevision

        menu.updateState(hidden.copy(update = TrayUpdateEntry("Downloading… 60%", enabled = false)))

        assertEquals(listOf(before + 1), emitted)
        assertEquals(before + 1, menu.currentRevision)
    }
}
