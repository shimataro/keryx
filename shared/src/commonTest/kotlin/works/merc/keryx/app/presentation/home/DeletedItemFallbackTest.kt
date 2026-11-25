package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class DeletedItemFallbackTest {
    private data class Row(val id: String, val name: String)

    private fun TestScope.fallbackFor(
        shown: MutableStateFlow<List<Row>>,
        isLive: suspend (String) -> Boolean,
    ): Pair<CoroutineScope, DeletedItemFallback<Row>> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return scope to DeletedItemFallback(scope, shown, dispatcher, isLive) { it.id }
    }

    @Test
    fun aStaleListStillShowingTheItemKeepsTheRequestPending() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin"), Row("t2", "Other")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        // A list query that read the pre-delete snapshot, delivered after the request.
        shown.value = listOf(Row("t1", "Kotlin"), Row("t2", "Renamed"))
        assertEquals(0, fellBack)
        // Checked once at the request (the value at hand still had the item) and once for the newer value.
        assertEquals(listOf("t1", "t1"), checked)

        shown.value = listOf(Row("t2", "Renamed"))
        assertEquals(1, fellBack)

        shown.value = listOf(Row("t2", "Again"))
        assertEquals(1, fellBack)
        scope.cancel()
    }

    /**
     * The list can already show the item re-created when the request is made (a write queued right
     * behind the delete landed first), so no newer value arrives to prompt a check: the request is
     * checked at once, found live, and dropped — a later deletion then moves nothing.
     */
    @Test
    fun anItemAlreadyShownRecreatedWhenRequestedIsDroppedAtOnce() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin")))
        val (scope, fallback) = fallbackFor(shown) { true }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        // A deletion arriving later, from a sync, say.
        shown.value = emptyList()

        assertEquals(0, fellBack)
        scope.cancel()
    }

    @Test
    fun anItemTheDatabaseShowsLiveAgainIsTreatedAsRecreated() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin")))
        val (scope, fallback) = fallbackFor(shown) { true }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        shown.value = listOf(Row("t1", "Kotlin"), Row("t2", "New"))
        // The request was dropped, so a later deletion (from a sync, say) moves nothing.
        shown.value = listOf(Row("t2", "New"))

        assertEquals(0, fellBack)
        scope.cancel()
    }

    @Test
    fun aListWithoutTheItemDuringACheckFallsBackOnceAndTheLateResultIsIgnored() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin")))
        val result = CompletableDeferred<Boolean>()
        val (scope, fallback) = fallbackFor(shown) { result.await() }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        shown.value = listOf(Row("t1", "Kotlin"), Row("t2", "Other"))
        shown.value = listOf(Row("t2", "Other"))
        assertEquals(1, fellBack)

        result.complete(true)
        testScheduler.advanceUntilIdle()
        shown.value = listOf(Row("t3", "Third"))

        assertEquals(1, fellBack)
        scope.cancel()
    }

    @Test
    fun aListThatAlreadyLacksTheItemFallsBackAtOnceWithoutACheck() = runTest {
        val shown = MutableStateFlow(listOf(Row("t2", "Other")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }

        assertEquals(1, fellBack)
        assertEquals(emptyList(), checked)
        scope.cancel()
    }

    @Test
    fun aFailedLivenessCheckIsNotUncaughtAndKeepsTheRequestPending() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin"), Row("t2", "Other")))
        // fallbackFor's scope has no handler; this one records what would otherwise be uncaught.
        val uncaught = mutableListOf<Throwable>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> uncaught += e })
        val fallback = DeletedItemFallback(
            scope, shown, dispatcher,
            isLive = { throw IllegalStateException("[SQLITE_BUSY] The database file is locked") },
        ) { it.id }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        // A stale list still showing the item starts a liveness check, which fails.
        shown.value = listOf(Row("t1", "Kotlin"), Row("t2", "Renamed"))
        assertEquals(emptyList(), uncaught)
        assertEquals(0, fellBack)

        // The request stayed pending, so the list that drops the item still falls back, once.
        shown.value = listOf(Row("t2", "Renamed"))
        assertEquals(1, fellBack)
        assertEquals(emptyList(), uncaught)
        scope.cancel()
    }

    // --- remapPending: the deleted item was re-keyed onto another id by a merge ---

    /** A pending request moved onto an id the list does not show falls back at once. */
    @Test
    fun aRequestRemappedOntoAnIdTheListLacksFallsBack() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "Kotlin"), Row("t2", "Other")))
        val (scope, fallback) = fallbackFor(shown) { false }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-old") { fellBack++ }
        assertEquals(0, fellBack)

        // The merge re-keyed t-old onto t-new, whose row (deleted) the list does not show; the re-key
        // is published before the list's next value arrives.
        fallback.remapPending { if (it == "t-old") "t-new" else it }
        assertEquals(1, fellBack)
        shown.value = listOf(Row("t2", "Other"))
        assertEquals(1, fellBack, "decided once")
        scope.cancel()
    }

    /**
     * A request moved onto an id the list still shows checks that row's liveness, as a new request
     * would, and then waits for the new id to leave the list.
     */
    @Test
    fun aRequestRemappedOntoAShownIdFollowsThatRow() = runTest {
        // The re-key is published while the list value at hand still shows the row under its new id.
        val shown = MutableStateFlow(listOf(Row("t-old", "Kotlin"), Row("t-new", "Kotlin"), Row("t2", "Other")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-old") { fellBack++ }
        checked.clear()

        fallback.remapPending { if (it == "t-old") "t-new" else it }
        assertEquals(listOf("t-new"), checked, "the new id's row is checked at once")
        assertEquals(0, fellBack)

        shown.value = listOf(Row("t-new", "Kotlin"), Row("t2", "Other"))
        assertEquals(0, fellBack, "still shown under its new id")
        shown.value = listOf(Row("t2", "Other"))
        assertEquals(1, fellBack)
        scope.cancel()
    }

    @Test
    fun aRequestRemappedOntoAStillShownLiveRowIsDropped() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "Kotlin")))
        val live = mutableSetOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> id in live }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-old") { fellBack++ }

        // The merge re-keyed t-old onto t-new and kept it alive (another device's newer revival).
        live += "t-new"
        shown.value = listOf(Row("t-old", "Kotlin"), Row("t-new", "Kotlin"))
        fallback.remapPending { if (it == "t-old") "t-new" else it }
        shown.value = listOf(Row("t-new", "Kotlin"))
        // A later deletion from elsewhere moves nothing: the request was dropped as live.
        shown.value = emptyList()

        assertEquals(0, fellBack)
        scope.cancel()
    }

    @Test
    fun aRemapThatMovesNoPendingIdChangesNothing() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "Kotlin")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0
        fallback.fallBackOnceHidden("t1") { fellBack++ }
        val checksBefore = checked.size

        fallback.remapPending { if (it == "other") "moved" else it }

        assertEquals(checksBefore, checked.size)
        shown.value = emptyList()
        assertEquals(1, fellBack)
        scope.cancel()
    }
}
