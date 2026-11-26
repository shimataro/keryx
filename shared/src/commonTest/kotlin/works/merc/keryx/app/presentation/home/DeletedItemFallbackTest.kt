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

    // --- a list that shows the re-keyed item under its new id before remapPending reaches the request ---

    /**
     * The merge publishes the re-key map before it notifies the list queries, so the list can deliver
     * the row under its new id while the request still holds the old one (the move is applied on
     * another coroutine). The request is judged in the current map's id space: the row is still
     * shown, so it does not fall back — for a tag or a folder alike, since both pass their own map.
     */
    @Test
    fun aListShowingTheNewIdBeforeTheRequestIsMovedDoesNotFallBack() = runTest {
        for (kind in listOf("tag", "folder")) {
            val shown = MutableStateFlow(listOf(Row("$kind-old", "News"), Row("other", "Other")))
            var published: Map<String, String> = emptyMap()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val checked = mutableListOf<String>()
            val fallback = DeletedItemFallback(
                scope, shown, dispatcher,
                isLive = { id -> checked += id; id == "$kind-new" },
                currentRemap = { { id -> published[id] ?: id } },
            ) { it.id }
            var fellBack = 0

            fallback.fallBackOnceHidden("$kind-old") { fellBack++ }
            // The merge: the map is published first, then the list re-runs and shows the new id ...
            published = mapOf("$kind-old" to "$kind-new")
            shown.value = listOf(Row("$kind-new", "News"), Row("other", "Other"))
            // ... before remapPending has been delivered to the request.
            assertEquals(0, fellBack, kind)

            fallback.remapPending { published[it] ?: it }
            // Moved onto the new id, which is live: the request is dropped without falling back, and a
            // later list lacking the row moves nothing.
            shown.value = listOf(Row("other", "Other"))
            assertEquals(0, fellBack, kind)
            scope.cancel()
        }
    }

    // --- lists before the map: another write re-runs the list over the committed re-key before the map is published ---

    private class CatchUpHarness(testScope: TestScope, val shown: MutableStateFlow<List<Row>>) {
        /** The map as the holders see it: stale until [catchUp] reads the committed one. */
        var published: Map<String, String> = emptyMap()
        var committed: Map<String, String> = emptyMap()
        var catchUps = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        var live: Set<String> = setOf("t-new")
        val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fallback = DeletedItemFallback(
            scope, shown, dispatcher,
            isLive = { id -> id in live },
            currentRemap = { { id -> published[id] ?: id } },
            catchUp = {
                catchUps++
                gate?.await()
                failure?.let { throw it }
                published = committed
            },
        ) { it.id }
    }

    /**
     * The re-key committed, and a list re-run by another write already shows the row under its new id,
     * while the published map is still the old one. The absence is not believed until the map has been
     * caught up from the committed state; the row is then found under its new id, so nothing falls back.
     */
    @Test
    fun aListAheadOfTheMapIsNotTakenForADeletionOnceTheMapIsCaughtUp() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-old") { fellBack++ }
        h.committed = mapOf("t-old" to "t-new")
        h.gate = CompletableDeferred()

        shown.value = listOf(Row("t-new", "News"), Row("other", "Other"))
        // Undecided while the catch-up is in flight: the stale map says the item is gone, and nothing acts on it.
        assertEquals(0, fellBack)
        assertEquals(1, h.catchUps)

        h.gate!!.complete(Unit)
        // What the ViewModel's re-key collector does once the catch-up has published the map.
        h.fallback.remapPending { h.published[it] ?: it }
        assertEquals(0, fellBack)
        // The request was dropped as live (t-new), so a later deletion moves nothing.
        shown.value = listOf(Row("other", "Other"))
        assertEquals(0, fellBack)
        h.scope.cancel()
    }

    /** After the catch-up an absence nothing explains is a real deletion: the fallback runs, once, and no loop follows. */
    @Test
    fun aRealDeletionStillFallsBackAfterTheCatchUp() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-old") { fellBack++ }

        shown.value = listOf(Row("other", "Other"))

        assertEquals(1, fellBack)
        assertEquals(1, h.catchUps)
        shown.value = listOf(Row("other", "Renamed"))
        assertEquals(1, fellBack)
        assertEquals(1, h.catchUps)
        h.scope.cancel()
    }

    /** Requested while the list already lacks the item: the same confirmation precedes the fallback. */
    @Test
    fun anItemAlreadyAbsentAtTheRequestIsConfirmedBeforeFallingBack() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-new", "News")))
        val h = CatchUpHarness(this, shown)
        h.committed = mapOf("t-old" to "t-new")
        var fellBack = 0

        h.fallback.fallBackOnceHidden("t-old") { fellBack++ }
        assertEquals(1, h.catchUps)
        h.fallback.remapPending { h.published[it] ?: it }
        // Row t-new is shown and live: dropped without falling back.
        shown.value = emptyList()
        assertEquals(0, fellBack)
        h.scope.cancel()
    }

    /** A catch-up that fails is best effort: the request stays pending, and the next list decides (asking again). */
    @Test
    fun aFailedCatchUpLeavesTheRequestPendingForTheNextList() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-old") { fellBack++ }
        h.failure = IllegalStateException("busy")

        shown.value = listOf(Row("other", "Other"))
        assertEquals(0, fellBack)

        h.failure = null
        shown.value = listOf(Row("other", "Again"))
        assertEquals(1, fellBack)
        assertEquals(2, h.catchUps)
        h.scope.cancel()
    }

    /** Without a re-key, a list lacking the item still falls back — the map changes nothing else. */
    @Test
    fun theCurrentMapDoesNotHideARealDeletion() = runTest {
        val shown = MutableStateFlow(listOf(Row("t1", "A"), Row("t2", "B")))
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fallback = DeletedItemFallback(
            scope, shown, dispatcher, isLive = { false },
            currentRemap = { { id -> if (id == "t9") "t2" else id } },
        ) { it.id }
        var fellBack = 0

        fallback.fallBackOnceHidden("t1") { fellBack++ }
        shown.value = listOf(Row("t2", "B"))

        assertEquals(1, fellBack)
        scope.cancel()
    }

    // --- remapPending: the deleted item was re-keyed onto another id by a merge ---

    /**
     * A pending request moved onto an id the list does not show, while the list still shows the old
     * id, stays pending: that list predates the re-key. The next list value lacking the new id
     * decides it.
     */
    @Test
    fun aRequestRemappedWhileTheListStillShowsTheOldIdWaitsForANewerList() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "Kotlin"), Row("t2", "Other")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-old") { fellBack++ }
        checked.clear()

        // The merge re-keyed t-old onto t-new (deleted); the re-key is published before the list's
        // next value arrives.
        fallback.remapPending { if (it == "t-old") "t-new" else it }
        assertEquals(0, fellBack, "a list still showing the old id proves nothing about the new one")
        assertEquals(listOf("t-new"), checked, "the new id's liveness is checked")

        shown.value = listOf(Row("t2", "Other"))
        assertEquals(1, fellBack)
        shown.value = emptyList()
        assertEquals(1, fellBack, "decided once")
        scope.cancel()
    }

    /**
     * The list is held at the old id while the re-key onto a live row is published: no fallback runs,
     * since the new row is live — the selection is not reset onto All while the item lives on.
     */
    @Test
    fun aRequestRemappedOntoALiveRowWhileTheListIsHeldAtTheOldIdDoesNotFallBack() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-old", "Kotlin")))
        val (scope, fallback) = fallbackFor(shown) { id -> id == "t-new" }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-old") { fellBack++ }

        fallback.remapPending { if (it == "t-old") "t-new" else it }
        assertEquals(0, fellBack)

        shown.value = listOf(Row("t-new", "Kotlin"))
        // Dropped as live: a later deletion from elsewhere moves nothing.
        shown.value = emptyList()
        assertEquals(0, fellBack)
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

    /**
     * Two re-keys published before the list's next value (Z -> M, then M -> A, the map folded as
     * `Z -> A`, `M -> A`): the list still shows Z, which neither M nor A is, yet it predates both
     * re-keys. The request stays pending, and a refreshed list without any of the three ids decides it.
     */
    @Test
    fun aRequestRemappedTwiceWhileTheListStillShowsTheFirstIdStaysPending() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-z", "Kotlin"), Row("t2", "Other")))
        val checked = mutableListOf<String>()
        val (scope, fallback) = fallbackFor(shown) { id -> checked += id; false }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-z") { fellBack++ }
        checked.clear()

        fallback.remapPending { if (it == "t-z") "t-m" else it }
        fallback.remapPending { if (it == "t-z" || it == "t-m") "t-a" else it }
        assertEquals(0, fellBack, "a list at the first id predates both re-keys")
        assertEquals(listOf("t-m", "t-a"), checked, "each new id's liveness is checked")

        shown.value = listOf(Row("t2", "Other"))
        assertEquals(1, fellBack, "a refreshed list without the item decides it")
        shown.value = emptyList()
        assertEquals(1, fellBack, "decided once")
        scope.cancel()
    }

    /** The same two re-keys, the item living on as A: the request is dropped, not fallen back. */
    @Test
    fun aRequestRemappedTwiceOntoALiveRowIsDroppedWhenTheListCatchesUp() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-z", "Kotlin")))
        val (scope, fallback) = fallbackFor(shown) { id -> id == "t-a" }
        var fellBack = 0
        fallback.fallBackOnceHidden("t-z") { fellBack++ }

        fallback.remapPending { if (it == "t-z") "t-m" else it }
        fallback.remapPending { if (it == "t-z" || it == "t-m") "t-a" else it }
        assertEquals(0, fellBack)

        shown.value = listOf(Row("t-a", "Kotlin"))
        shown.value = emptyList()
        assertEquals(0, fellBack, "dropped as live: a later deletion from elsewhere moves nothing")
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

    // --- remapPending ahead of the list: a request moved onto an id the list no longer shows ---

    /**
     * The map's A -> B reaches [DeletedItemFallback.remapPending] while the list already shows C (the
     * database holding B -> C, not yet published). The absence of B is confirmed against the committed
     * map first, which joins A and B to C: the row is shown, so nothing falls back, and once the request
     * follows the map onto C it is dropped as live.
     */
    @Test
    fun aRequestRemappedOntoAnIdTheListHasMovedPastIsConfirmedBeforeFallingBack() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-a", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        h.live = setOf("t-c")
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-a") { fellBack++ }
        h.published = mapOf("t-a" to "t-b")
        h.committed = mapOf("t-a" to "t-c", "t-b" to "t-c")
        h.gate = CompletableDeferred()

        shown.value = listOf(Row("t-c", "News"), Row("other", "Other"))
        h.fallback.remapPending { h.published[it] ?: it }
        assertEquals(0, fellBack, "an absence under the delivered map is not believed before the catch-up")
        assertEquals(2, h.catchUps)

        h.gate!!.complete(Unit)
        assertEquals(0, fellBack, "the caught-up map shows the row under C")
        h.fallback.remapPending { h.published[it] ?: it }
        // Dropped as live (t-c): a later deletion from elsewhere moves nothing.
        shown.value = listOf(Row("other", "Other"))
        assertEquals(0, fellBack)
        h.scope.cancel()
    }

    /** As above, with the catch-up failing: the request stays pending, and the next list decides it. */
    @Test
    fun aRequestRemappedOntoAnAbsentIdStaysPendingWhenTheCatchUpFails() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-a", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        h.live = setOf("t-c")
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-a") { fellBack++ }
        h.published = mapOf("t-a" to "t-b")
        h.committed = mapOf("t-a" to "t-c", "t-b" to "t-c")
        h.failure = IllegalStateException("busy")

        shown.value = listOf(Row("t-c", "News"), Row("other", "Other"))
        h.fallback.remapPending { h.published[it] ?: it }
        assertEquals(0, fellBack, "a failed catch-up decides nothing")
        assertEquals(2, h.catchUps)

        h.failure = null
        shown.value = listOf(Row("t-c", "News"), Row("other", "Again"))
        assertEquals(3, h.catchUps)
        assertEquals(0, fellBack)
        h.fallback.remapPending { h.published[it] ?: it }
        shown.value = listOf(Row("other", "Again"))
        assertEquals(0, fellBack, "dropped as live once it follows the map onto C")
        h.scope.cancel()
    }

    /** The new id really is gone: the fallback runs once, after the catch-up confirmed it. */
    @Test
    fun aRequestRemappedOntoAGoneIdFallsBackOnceAfterTheCatchUp() = runTest {
        val shown = MutableStateFlow(listOf(Row("t-a", "News"), Row("other", "Other")))
        val h = CatchUpHarness(this, shown)
        h.live = emptySet()
        var fellBack = 0
        h.fallback.fallBackOnceHidden("t-a") { fellBack++ }
        h.published = mapOf("t-a" to "t-b")
        h.committed = mapOf("t-a" to "t-b")
        h.gate = CompletableDeferred()

        shown.value = listOf(Row("other", "Other"))
        h.fallback.remapPending { h.published[it] ?: it }
        assertEquals(0, fellBack, "not before the catch-up")

        h.gate!!.complete(Unit)
        assertEquals(1, fellBack)
        shown.value = listOf(Row("other", "Again"))
        assertEquals(1, fellBack, "decided once")
        h.scope.cancel()
    }
}
