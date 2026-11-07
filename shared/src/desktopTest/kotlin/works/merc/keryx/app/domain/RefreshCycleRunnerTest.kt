package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NetworkCondition
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import java.util.Collections
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RefreshCycleRunner] against a real DB, a MockEngine feed fetcher, and a real [SyncRepository]
 * whose `cloudProvider` returns `null` (so a sync is an instant `Ok`) but records each call — the
 * observable "a sync ran" signal. `runBlocking` rather than `runTest`: MockEngine plus
 * `HttpTimeout` under virtual time can misfire (see testing.md).
 */
class RefreshCycleRunnerTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "refresh-cycle-runner-test-${Random.nextInt()}")
    private val fixtures = RefreshCycleRunnerFixtures(dir)

    @AfterTest
    fun tearDown() {
        fixtures.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun withDb(block: suspend CoroutineScope.(SqlDriver, KeryxDatabase) -> Unit): Unit = runBlocking {
        val (driver, db) = inMemoryDb()
        try {
            block(driver, db)
        } finally {
            driver.close()
        }
    }

    @Test
    fun runRefreshesNotifiesThenSyncsByDefault() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun automaticRunSkipsTheRefreshWhileTheOsIsSavingDataButStillReachesTheSync() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, network = NetworkCondition(metered = true, dataSaver = true))

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertEquals(listOf("sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun automaticRunRefreshesOnAMeteredNetworkWithoutDataSaver() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, network = NetworkCondition(metered = true, dataSaver = false))

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun manualRunRefreshesEvenWhileTheOsIsSavingData() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, network = NetworkCondition(metered = true, dataSaver = true))

        f.runner.run(trigger = SyncTrigger.MANUAL)
        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertIs<RefreshCycleRunner.CycleOutcome.Ran>(outcome)
        assertEquals(2, f.log.count { it == "fetch:https://ex.com/f1" })
    }

    @Test
    fun runSyncsFirstWithSyncThenRefresh() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        f.runner.run(order = RefreshCycleRunner.CycleOrder.SYNC_THEN_REFRESH, trigger = SyncTrigger.AUTOMATIC)

        // The trailing "sync" is the short-window sync the cycle schedules for what its refresh wrote
        // (scheduleSync consults the provider first); a refresh-then-sync cycle schedules none.
        assertEquals(listOf("sync", "fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun runDoesNotSyncWhenNoProviderIsConnected() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, connected = false)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertNull(result)
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1"), f.log.toList())
    }

    @Test
    fun runWrapsEachStageInTheStepWrapper() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        val steps = mutableListOf<String>()

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            steps += "$name:start"
            block()
            steps += "$name:end"
        }

        assertEquals(listOf("feedRefresh:start", "feedRefresh:end", "sync:start", "sync:end"), steps)
    }

    @Test
    fun runKeepsGoingWhenTheStepWrapperSwallowsAFailedStage() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        val result = f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            // Stand-in for runMaintenanceStep: the refresh stage "fails" and is swallowed.
            if (name == "feedRefresh") return@run
            block()
        }

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(listOf("sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIsNotIdleAnywhereInTheCycle() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)
        val snapshots = Collections.synchronizedList(mutableListOf<ActivitySnapshot>())

        val cycle = async {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { _, block ->
                snapshots += f.activityCenter.activity.value // between stages: only the cycle is up
                block()
            }
        }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        assertTrue(f.activityCenter.activity.value.refreshCycleRunning)
        gate.complete(Unit)
        cycle.await()

        assertEquals(2, snapshots.size)
        assertTrue(snapshots.all { !it.idle }, "every stage boundary must be busy: $snapshots")
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIfIdleReturnsBusyWithoutStartingWhileAnythingIsInFlight() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val busy = launch { f.activityCenter.trackSync { entered.complete(Unit); gate.await() } }
        entered.await()

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Busy, outcome)
        assertTrue(f.log.isEmpty(), "nothing may be fetched or synced: ${f.log}")
        gate.complete(Unit)
        busy.join()
    }

    @Test
    fun runIfIdleRunsTheCycleForTheSelectedFeedOnly() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        db.insertFeed("f2", url = "https://ex.com/f2")
        val f = fixtures.create(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.Feed("f2"), SyncTrigger.MANUAL)

        assertEquals(
            RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit), RefreshOutcome(newArticleCount = 1, unreachable = false)),
            outcome,
        )
        assertEquals(listOf("fetch:https://ex.com/f2", "notify:new:1", "sync"), f.log.toList())
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun runIfIdleDoesNothingForASelectionCoveringNoSubscribedFeed() = withDb { driver, db ->
        db.insertFolder("d1", "Empty")
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.Folder("d1"), SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.NothingToRefresh, outcome)
        assertTrue(f.log.isEmpty(), "nothing may be fetched or synced: ${f.log}")
        assertFalse(f.activityCenter.activity.value.refreshCycleRunning)
    }

    @Test
    fun runIfIdleStillSyncsForAllWithNoFeeds() = withDb { driver, db ->
        val f = fixtures.create(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit)), outcome)
        assertEquals(listOf("sync"), f.log.toList())
    }

    @Test
    fun runIfIdleReportsANullSyncResultWhenNotConnected() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, connected = false)

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshCycleRunner.CycleOutcome.Ran(null, RefreshOutcome(newArticleCount = 1, unreachable = false)), outcome)
        assertFalse("sync" in f.log)
    }

    @Test
    fun aManualCycleReturnsHowItsRefreshWent() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        db.insertFeed("f2", url = "https://ex.com/f2")
        val f = fixtures.create(driver, db)

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(
            RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit), RefreshOutcome(newArticleCount = 2, unreachable = false)),
            outcome,
        )
        assertTrue(f.notificationCenter.items.value.isEmpty())
    }

    @Test
    fun runIfIdleCallsOnStartedOnlyForACycleThatRuns() = withDb { driver, db ->
        db.insertFolder("empty", name = "Empty")
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        var starts = 0

        f.runner.runIfIdle(ArticleFilter.Folder("empty"), SyncTrigger.MANUAL) { starts++ }
        assertEquals(0, starts, "nothing to refresh starts nothing")

        f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) {
            starts++
            assertTrue(f.activityCenter.activity.value.refreshCycleRunning, "called once the cycle is claimed")
            assertTrue(f.log.isEmpty(), "called before any stage runs: ${f.log}")
        }
        assertEquals(1, starts)
    }

    @Test
    fun anUnreachableRefreshReportsItAndWarnsOnceHoweverOftenItRecurs() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchStatus = { null })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)
        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = true), assertIs<RefreshCycleRunner.CycleOutcome.Ran>(outcome).refresh)
        val warning = f.notificationCenter.items.value.single()
        assertEquals(AppNotificationLevel.WARNING, warning.level)
        assertEquals(NotificationText.RefreshFailed, warning.text)
        assertEquals(AppNotificationAction.ShowInfoDialog(InfoDialogText.REFRESH_FAILED), warning.action)
    }

    @Test
    fun aStageFailingAfterTheRefreshStillReportsIt() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchStatus = { null })

        assertFailsWith<IllegalStateException> {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
                block()
                check(name != "feedRefresh") { "failed after the refresh" }
            }
        }

        assertEquals(NotificationText.RefreshFailed, f.notificationCenter.items.value.single().text)
    }

    @Test
    fun anAutomaticRunPublishesItsOutcomeUntilTheWholeCycleEnds() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)
        assertNull(f.runner.joinableAutomaticCycle())

        val cycle = async { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val joinable = assertNotNull(f.runner.joinableAutomaticCycle())
        assertFalse(joinable.isCompleted)

        gate.complete(Unit)
        cycle.await()

        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { joinable.await() })
        assertTrue(f.log.contains("sync"))
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun anAutomaticRunIfIdlePublishesItsOutcomeToo() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)

        val cycle = async { f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val joinable = assertNotNull(f.runner.joinableAutomaticCycle())

        gate.complete(Unit)
        assertIs<RefreshCycleRunner.CycleOutcome.Ran>(cycle.await())

        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { joinable.await() })
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun aManualCycleNeverPublishesAJoinableCycle() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)

        val run = async { f.runner.run(trigger = SyncTrigger.MANUAL) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        assertNull(f.runner.joinableAutomaticCycle())
        gate.complete(Unit)
        run.await()

        val gate2 = CompletableDeferred<Unit>()
        val f2 = fixtures.create(driver, db, fetchGate = gate2)
        val idle = async { f2.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) }
        withTimeout(5_000) { f2.activityCenter.activity.first { it.feedRefreshing } }
        assertNull(f2.runner.joinableAutomaticCycle())
        gate2.complete(Unit)
        idle.await()
        assertNull(f2.runner.joinableAutomaticCycle())
    }

    @Test
    fun aManualRunIfIdleDuringAnAutomaticCycleJoinsItWithoutStarting() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)
        val cycle = async { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val running = assertNotNull(f.runner.joinableAutomaticCycle())
        var started = false

        val joined = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) { started = true }

        assertTrue(running === assertIs<RefreshCycleRunner.CycleOutcome.Joined>(joined).cycle)
        assertFalse(started, "joining must not start a cycle of its own")
        gate.complete(Unit)
        cycle.await()
        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { joined.cycle.await() })
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun anOverlappedAutomaticCycleStaysJoinableAfterALaterOneEnds() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        val atSync = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val earlier = async {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
                if (name == "sync") {
                    atSync.complete(Unit)
                    gate.await()
                }
                block()
            }
        }
        withTimeout(5_000) { atSync.await() }
        val running = assertNotNull(f.runner.joinableAutomaticCycle())

        // A later automatic cycle starts while the earlier one is still running, and ends first.
        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        val joined = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)
        assertTrue(running === assertIs<RefreshCycleRunner.CycleOutcome.Joined>(joined).cycle)
        gate.complete(Unit)
        earlier.await()
        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { joined.cycle.await() })
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun aBusyAutomaticRunIfIdlePublishesNothingAndKeepsTheRunningCycle()= withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)
        val cycle = async { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val running = assertNotNull(f.runner.joinableAutomaticCycle())

        val busy = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC)

        assertEquals(RefreshCycleRunner.CycleOutcome.Busy, busy)
        assertTrue(running === f.runner.joinableAutomaticCycle(), "the busy attempt must not replace the running cycle")
        gate.complete(Unit)
        cycle.await()
        // The busy attempt's own completion must not have cleared or completed anything of the real one.
        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), running.await())
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun aBusyAutomaticRunIfIdleDuringASyncAlonePublishesNothing() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val busy = launch { f.activityCenter.trackSync { entered.complete(Unit); gate.await() } }
        entered.await()

        assertEquals(RefreshCycleRunner.CycleOutcome.Busy, f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC))

        assertNull(f.runner.joinableAutomaticCycle())
        gate.complete(Unit)
        busy.join()
    }

    @Test
    fun aStageThrowingStillCompletesAndClearsTheJoinableCycle() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        var joinable: kotlinx.coroutines.Deferred<RefreshOutcome?>? = null

        assertFailsWith<IllegalStateException> {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
                joinable = f.runner.joinableAutomaticCycle()
                block()
                check(name != "feedRefresh") { "failed after the refresh" }
            }
        }

        val published = assertNotNull(joinable)
        // The refresh finished before the failure, so its outcome is still reported to a joiner.
        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { published.await() })
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun aCycleFailingBeforeAnyRefreshCompletesTheJoinableCycleWithNull() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)
        var joinable: kotlinx.coroutines.Deferred<RefreshOutcome?>? = null

        assertFailsWith<IllegalStateException> {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { _, _ ->
                joinable = f.runner.joinableAutomaticCycle()
                error("failed before the refresh")
            }
        }

        assertNull(withTimeout(5_000) { assertNotNull(joinable).await() })
        assertNull(f.runner.joinableAutomaticCycle())
    }

    @Test
    fun aSingleFeedRefreshReturnsItsOutcome() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        val outcome = f.runner.refreshFeed("f1")

        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), outcome)
        assertEquals(listOf("fetch:https://ex.com/f1"), f.log.toList(), "a single feed's refresh neither notifies nor syncs")
    }

    @Test
    fun aRefreshWhoseFeedsAllAnsweredWithErrorsIsNotUnreachable() = withDb { driver, db ->
        db.insertFeed("gone", url = "https://ex.com/gone")
        db.insertFeed("missing", url = "https://ex.com/missing")
        db.insertFeed("broken", url = "https://ex.com/broken")
        val statuses = mapOf(
            "https://ex.com/gone" to HttpStatusCode.Gone,
            "https://ex.com/missing" to HttpStatusCode.NotFound,
            "https://ex.com/broken" to HttpStatusCode.ServiceUnavailable,
        )
        val f = fixtures.create(driver, db, fetchStatus = { statuses.getValue(it) })

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = false), assertIs<RefreshCycleRunner.CycleOutcome.Ran>(outcome).refresh)
        assertTrue(
            f.notificationCenter.items.value.none { it.text == NotificationText.RefreshFailed },
            "a feed that answered is not a connection problem: ${f.notificationCenter.items.value}",
        )
    }

    @Test
    fun aSingleGoneFeedOnlyRaisesItsOwnNotification() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchStatus = { HttpStatusCode.Gone })

        f.runner.refreshFeed("f1")

        assertIs<NotificationText.FeedGone>(f.notificationCenter.items.value.single().text)
    }

    @Test
    fun anUnparsableFeedIsNotUnreachable() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchBody = "not a feed")

        val outcome = f.runner.refreshFeed("f1")

        assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = false), outcome)
        assertTrue(f.notificationCenter.items.value.isEmpty())
    }

    @Test
    fun anUnreachableFeedAlongsideOneThatAnsweredIsNotUnreachable() = withDb { driver, db ->
        db.insertFeed("down", url = "https://ex.com/down")
        db.insertFeed("missing", url = "https://ex.com/missing")
        val f = fixtures.create(driver, db, fetchStatus = { if (it.endsWith("missing")) HttpStatusCode.NotFound else null })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertTrue(f.notificationCenter.items.value.isEmpty())
    }

    @Test
    fun anAutomaticRunInFrontHoldsBackTheNotificationButStillReportsAndSyncs() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val front = AppForeground().also { it.set(true) }
        val f = fixtures.create(driver, db, fetchGate = gate, appForeground = front)

        val cycle = async { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val joinable = assertNotNull(f.runner.joinableAutomaticCycle())
        gate.complete(Unit)
        cycle.await()

        assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), withTimeout(5_000) { joinable.await() })
        assertEquals(listOf("fetch:https://ex.com/f1", "sync"), f.log.toList())
    }

    @Test
    fun anAutomaticRunIfIdleInFrontStillReturnsItsRefreshOutcome() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, appForeground = AppForeground().also { it.set(true) })

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC)

        assertEquals(
            RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit), RefreshOutcome(newArticleCount = 1, unreachable = false)),
            outcome,
        )
        assertTrue(f.log.none { it.startsWith("notify:") })
    }

    @Test
    fun anAutomaticRunNotInFrontNotifies() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val front = AppForeground().also { it.set(true); it.set(false) }
        val f = fixtures.create(driver, db, appForeground = front)

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun aManualRunInFrontStillNotifies() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, appForeground = AppForeground().also { it.set(true) })

        val outcome = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)

        assertIs<RefreshCycleRunner.CycleOutcome.Ran>(outcome)
        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun anUnreachableAutomaticRefreshInFrontStillWarnsInTheNotificationCenter() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchStatus = { null }, appForeground = AppForeground().also { it.set(true) })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC)

        assertEquals(NotificationText.RefreshFailed, f.notificationCenter.items.value.single().text)
    }

    @Test
    fun theFrontStateIsReadWhenTheRefreshFinishesNotWhenItStarts() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val front = AppForeground()
        val f = fixtures.create(driver, db, fetchGate = gate, appForeground = front)

        val cycle = async { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        front.set(true)
        gate.complete(Unit)
        cycle.await()

        assertTrue(f.log.none { it.startsWith("notify:") }, "${f.log}")
    }

    @Test
    fun aUserActionJoiningBeforeTheRefreshFinishesGetsTheNotificationInFront() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, appForeground = AppForeground().also { it.set(true) })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            if (name == "feedRefresh") assertNotNull(f.runner.joinAutomaticCycle())
            block()
        }

        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun aUserActionJoiningAfterTheNotificationWasHeldBackPostsIt() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, appForeground = AppForeground().also { it.set(true) })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            block()
            if (name == "feedRefresh") {
                assertTrue(f.log.none { it.startsWith("notify:") }, "held back until joined")
                assertNotNull(f.runner.joinAutomaticCycle())
            }
        }

        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    @Test
    fun joiningTheSameCycleTwicePostsTheNotificationOnce() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, appForeground = AppForeground().also { it.set(true) })

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            if (name == "feedRefresh") assertNotNull(f.runner.joinAutomaticCycle())
            block()
            if (name == "feedRefresh") assertNotNull(f.runner.joinAutomaticCycle())
        }

        assertEquals(1, f.log.count { it.startsWith("notify:") }, "${f.log}")
    }

    @Test
    fun joiningAnAutomaticCycleNotInFrontDoesNotPostASecondNotification() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db)

        f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
            block()
            if (name == "feedRefresh") assertNotNull(f.runner.joinAutomaticCycle())
        }

        assertEquals(listOf("fetch:https://ex.com/f1", "notify:new:1", "sync"), f.log.toList())
    }

    private suspend fun RefreshCycleRunnerFixture.awaitSyncing() {
        withTimeout(5_000) { activityCenter.activity.first { it.syncing } }
    }

    @Test
    fun aManualCycleEndsItsRefreshBeforeItsSyncAndReturnsOnlyAfterIt() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, syncGate = syncGate)
        val refreshEnded = CompletableDeferred<Deferred<RefreshOutcome?>>()
        try {
            val cycle = async(Dispatchers.Default) {
                f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) { refreshEnded.complete(it) }
            }
            f.awaitSyncing()
            val deferred = withTimeout(5_000) { refreshEnded.await() }

            assertTrue(deferred.isCompleted, "the refresh stage has ended while the sync is still running")
            assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), deferred.await())
            assertFalse(cycle.isCompleted, "runIfIdle itself returns only after the sync")

            syncGate.complete(Unit)
            assertEquals(
                RefreshCycleRunner.CycleOutcome.Ran(Result.Ok(Unit), RefreshOutcome(newArticleCount = 1, unreachable = false)),
                withTimeout(5_000) { cycle.await() },
            )
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun anUnreachableRefreshIsReportedBeforeTheSyncStarts() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchStatus = { null }, syncGate = syncGate)
        val refreshEnded = CompletableDeferred<Deferred<RefreshOutcome?>>()
        try {
            val cycle = async(Dispatchers.Default) {
                f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) { refreshEnded.complete(it) }
            }
            f.awaitSyncing()

            assertEquals(NotificationText.RefreshFailed, f.notificationCenter.items.value.single().text)
            assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = true), withTimeout(5_000) { refreshEnded.await() }.await())

            syncGate.complete(Unit)
            withTimeout(5_000) { cycle.await() }
            assertEquals(1, f.notificationCenter.items.value.size, "reported once, not again at the cycle's end")
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun joiningAnUnreachableAutomaticCycleDuringItsSyncRestoresAWarningDismissedMeanwhile() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchStatus = { null }, syncGate = syncGate)
        try {
            val cycle = async(Dispatchers.Default) { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            f.awaitSyncing()
            val posted = f.notificationCenter.items.value.single()
            assertEquals(NotificationText.RefreshFailed, posted.text)
            f.notificationCenter.dismiss(posted.id)
            assertTrue(f.notificationCenter.items.value.isEmpty())

            val joined = assertIs<RefreshCycleRunner.CycleOutcome.Joined>(
                f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL),
            )

            assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = true), joined.cycle.await())
            val restored = f.notificationCenter.items.value.single()
            assertEquals(NotificationText.RefreshFailed, restored.text)
            assertEquals(AppNotificationAction.ShowInfoDialog(InfoDialogText.REFRESH_FAILED), restored.action)

            syncGate.complete(Unit)
            withTimeout(5_000) { cycle.await() }
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun joiningAnUnreachableAutomaticCycleKeepsASingleWarningThatWasNotDismissed() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchStatus = { null }, syncGate = syncGate)
        try {
            val cycle = async(Dispatchers.Default) { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            f.awaitSyncing()

            assertIs<RefreshCycleRunner.CycleOutcome.Joined>(f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL))

            assertEquals(NotificationText.RefreshFailed, f.notificationCenter.items.value.single().text)

            syncGate.complete(Unit)
            withTimeout(5_000) { cycle.await() }
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun joiningAReachableAutomaticCycleRestoresNoWarning() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, syncGate = syncGate)
        try {
            val cycle = async(Dispatchers.Default) { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            f.awaitSyncing()

            assertIs<RefreshCycleRunner.CycleOutcome.Joined>(f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL))

            assertTrue(f.notificationCenter.items.value.none { it.text == NotificationText.RefreshFailed })

            syncGate.complete(Unit)
            withTimeout(5_000) { cycle.await() }
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun aJoinableAutomaticCycleIsCompleteDuringItsSyncButStaysJoinableUntilTheSyncEnds() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, syncGate = syncGate)
        try {
            val cycle = async(Dispatchers.Default) { f.runner.run(trigger = SyncTrigger.AUTOMATIC) }
            f.awaitSyncing()

            val joinable = assertNotNull(f.runner.joinableAutomaticCycle(), "still joinable while it syncs")
            assertTrue(joinable.isCompleted)
            assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), joinable.await())

            // A manual action during that sync joins the automatic cycle and gets its result at once.
            val joined = f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL)
            val outcome = assertIs<RefreshCycleRunner.CycleOutcome.Joined>(joined)
            assertTrue(outcome.cycle.isCompleted)
            assertEquals(RefreshOutcome(newArticleCount = 1, unreachable = false), outcome.cycle.await())
            assertFalse(cycle.isCompleted)

            syncGate.complete(Unit)
            withTimeout(5_000) { cycle.await() }
            assertNull(f.runner.joinableAutomaticCycle())
        } finally {
            syncGate.complete(Unit)
        }
    }

    @Test
    fun aStageFailingAfterTheRefreshCompletesTheDeferredOnceAndReportsOnce() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val f = fixtures.create(driver, db, fetchStatus = { null })
        var joinable: Deferred<RefreshOutcome?>? = null

        assertFailsWith<IllegalStateException> {
            f.runner.run(trigger = SyncTrigger.AUTOMATIC) { name, block ->
                joinable = f.runner.joinableAutomaticCycle()
                block()
                check(name != "feedRefresh") { "failed after the refresh" }
            }
        }

        val deferred = assertNotNull(joinable)
        assertEquals(RefreshOutcome(newArticleCount = 0, unreachable = true), withTimeout(5_000) { deferred.await() })
        assertEquals(NotificationText.RefreshFailed, f.notificationCenter.items.value.single().text)
        assertFalse("sync" in f.log, "the sync stage never ran")
    }

    @Test
    fun aCycleCancelledMidRefreshCompletesTheDeferredWithNullAndReportsNothing() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val gate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, fetchGate = gate)
        val refreshEnded = CompletableDeferred<Deferred<RefreshOutcome?>>()

        val cycle = launch { f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.MANUAL) { refreshEnded.complete(it) } }
        withTimeout(5_000) { f.activityCenter.activity.first { it.feedRefreshing } }
        val deferred = refreshEnded.await()
        assertFalse(deferred.isCompleted)

        cycle.cancelAndJoin()

        assertTrue(deferred.isCompleted, "a waiter on the deferred must never hang")
        assertNull(deferred.await())
        assertTrue(f.notificationCenter.items.value.isEmpty())
        assertFalse("sync" in f.log)
        assertTrue(f.activityCenter.activity.value.idle)
    }

    @Test
    fun aDataSaverSkippedAutomaticRefreshCompletesTheDeferredWithNullBeforeTheSync() = withDb { driver, db ->
        db.insertFeed("f1", url = "https://ex.com/f1")
        val syncGate = CompletableDeferred<Unit>()
        val f = fixtures.create(driver, db, network = NetworkCondition(metered = true, dataSaver = true), syncGate = syncGate)
        val refreshEnded = CompletableDeferred<Deferred<RefreshOutcome?>>()
        try {
            val cycle = async(Dispatchers.Default) {
                f.runner.runIfIdle(ArticleFilter.All, SyncTrigger.AUTOMATIC) { refreshEnded.complete(it) }
            }
            f.awaitSyncing()
            val deferred = withTimeout(5_000) { refreshEnded.await() }

            assertTrue(deferred.isCompleted)
            assertNull(deferred.await())
            assertTrue(f.notificationCenter.items.value.isEmpty())

            syncGate.complete(Unit)
            assertIs<RefreshCycleRunner.CycleOutcome.Ran>(withTimeout(5_000) { cycle.await() })
            assertEquals(listOf("sync"), f.log.toList())
        } finally {
            syncGate.complete(Unit)
        }
    }
}
