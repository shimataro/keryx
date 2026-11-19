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
        assertEquals(listOf("t1"), checked)

        shown.value = listOf(Row("t2", "Renamed"))
        assertEquals(1, fellBack)

        shown.value = listOf(Row("t2", "Again"))
        assertEquals(1, fellBack)
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
}
