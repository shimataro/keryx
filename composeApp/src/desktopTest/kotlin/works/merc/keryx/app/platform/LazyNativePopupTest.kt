package works.merc.keryx.app.platform

import java.awt.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [LazyNativePopup]'s contract: nothing native exists until the first right-click.
 *
 * This cannot be exercised through a Compose UI test — `LocalNativeWindow` is
 * `staticCompositionLocalOf { null }`, so `attach`/`detach` are no-ops there and a rendered row
 * would look identical whether or not the widgets were built. Injecting a fake backend through the
 * `factory` seam is the only way to observe the difference.
 */
class LazyNativePopupTest {

    private class FakeHandle(val builtFrom: List<NativeMenuEntry>) : NativePopupHandle {
        val synced = mutableListOf<List<NativeMenuEntry>>()
        var attachCount = 0
        var detachCount = 0
        val shownAfterSyncs = mutableListOf<Int>()

        override fun sync(items: List<NativeMenuEntry>) {
            synced += items
        }

        override fun attach(window: NativeWindowHandle?) {
            attachCount++
        }

        override fun detach(window: NativeWindowHandle?) {
            detachCount++
        }

        override fun show(invoker: Component, x: Int, y: Int) {
            shownAfterSyncs += synced.size
        }
    }

    private class Recorder {
        val built = mutableListOf<FakeHandle>()
        val factory: (List<NativeMenuEntry>, () -> List<NativeMenuEntry>) -> NativePopupHandle =
            { entries, _ -> FakeHandle(entries).also { built += it } }
    }

    private val invoker = java.awt.Label()

    private fun item(label: String): NativeMenuEntry = NativeMenuItem(label) {}

    private fun check(label: String, checked: Boolean): NativeMenuEntry =
        NativeCheckMenuItem(label, checked) {}

    @Test
    fun buildsNothingUntilTheFirstShow() {
        val recorder = Recorder()
        LazyNativePopup(window = null, currentItems = { listOf(item("A")) }, factory = recorder.factory)

        assertEquals(0, recorder.built.size)
    }

    @Test
    fun buildsAndAttachesExactlyOnceOnTheFirstShow() {
        val recorder = Recorder()
        val entries = listOf(item("A"), item("B"))
        val popup = LazyNativePopup(null, { entries }, recorder.factory)

        popup.showFor(entries, invoker, 1, 2)

        assertEquals(1, recorder.built.size)
        assertEquals(entries, recorder.built[0].builtFrom)
        assertEquals(1, recorder.built[0].attachCount)
    }

    @Test
    fun reusesTheWidgetsWhenTheShapeIsUnchanged() {
        val recorder = Recorder()
        val first = listOf(item("A"), item("B"))
        val popup = LazyNativePopup(null, { first }, recorder.factory)

        popup.showFor(first, invoker, 1, 2)
        // Same kinds in the same order: a relabel, not a rebuild.
        popup.showFor(listOf(item("A2"), item("B2")), invoker, 1, 2)

        assertEquals(1, recorder.built.size)
    }

    @Test
    fun rebuildsAndDetachesTheOldWidgetsWhenTheShapeChanges() {
        val recorder = Recorder()
        val first = listOf(item("A"))
        val popup = LazyNativePopup(null, { first }, recorder.factory)

        popup.showFor(first, invoker, 1, 2)
        popup.showFor(listOf(item("A"), check("B", checked = true)), invoker, 1, 2)

        assertEquals(2, recorder.built.size)
        assertEquals(1, recorder.built[0].detachCount)
        assertEquals(1, recorder.built[1].attachCount)
    }

    @Test
    fun syncsWithTheEntriesPassedToShowAndBeforeShowing() {
        val recorder = Recorder()
        val built = listOf(item("A"))
        val popup = LazyNativePopup(null, { built }, recorder.factory)
        val fresh = listOf(item("A renamed"))

        popup.showFor(fresh, invoker, 1, 2)

        val handle = recorder.built.single()
        assertEquals(listOf(fresh), handle.synced)
        // show() observed a completed sync, so the menu can never be displayed with stale labels.
        assertEquals(listOf(1), handle.shownAfterSyncs)
    }

    @Test
    fun doesNotRelabelWhenTheRenderedContentIsUnchanged() {
        val recorder = Recorder()
        val entries = listOf(item("A"))
        val popup = LazyNativePopup(null, { entries }, recorder.factory)

        popup.showFor(entries, invoker, 1, 2)
        popup.showFor(listOf(item("A")), invoker, 1, 2)

        assertEquals(1, recorder.built.single().synced.size)
    }

    @Test
    fun relabelsWhenOnlyACheckStateChanged() {
        val recorder = Recorder()
        val off = listOf(check("A", checked = false))
        val popup = LazyNativePopup(null, { off }, recorder.factory)

        popup.showFor(off, invoker, 1, 2)
        popup.showFor(listOf(check("A", checked = true)), invoker, 1, 2)

        assertEquals(1, recorder.built.size, "a check-state flip must not rebuild the widgets")
        assertEquals(2, recorder.built.single().synced.size)
    }

    @Test
    fun disposeDetachesWhateverWasBuilt() {
        val recorder = Recorder()
        val entries = listOf(item("A"))
        val popup = LazyNativePopup(null, { entries }, recorder.factory)

        popup.showFor(entries, invoker, 1, 2)
        popup.dispose()

        assertEquals(1, recorder.built.single().detachCount)
    }

    @Test
    fun disposeIsHarmlessWhenNothingWasEverBuilt() {
        val recorder = Recorder()
        val popup = LazyNativePopup(null, { listOf(item("A")) }, recorder.factory)

        popup.dispose()

        assertTrue(recorder.built.isEmpty())
    }

    @Test
    fun rebuildsAfterDisposeSoAReattachedCallSiteStillWorks() {
        val recorder = Recorder()
        val entries = listOf(item("A"))
        val popup = LazyNativePopup(null, { entries }, recorder.factory)

        popup.showFor(entries, invoker, 1, 2)
        popup.dispose()
        popup.showFor(entries, invoker, 1, 2)

        assertEquals(2, recorder.built.size)
        assertEquals(1, recorder.built[1].attachCount)
    }
}
