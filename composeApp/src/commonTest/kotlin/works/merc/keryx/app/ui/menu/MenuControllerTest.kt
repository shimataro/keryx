package works.merc.keryx.app.ui.menu

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.ui.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MenuController] bridges the desktop application menu bar (built outside any screen's
 * composition) to whichever screen owns the target state. These tests cover its two pieces of
 * shared state — [MenuController.currentScreen] and [MenuController.searchFieldFocused] — and the
 * one-shot [MenuController.commands] flow, including the [MenuCommand.RenameFeed] /
 * [MenuCommand.UnsubscribeFeed] commands the Feed menu's rename/unsubscribe items send.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuControllerTest {

    @Test
    fun currentScreenDefaultsToSetup() {
        val controller = MenuController()
        assertEquals(Screen.Setup, controller.currentScreen.value)
    }

    @Test
    fun searchFieldFocusedDefaultsToFalse() {
        val controller = MenuController()
        assertFalse(controller.searchFieldFocused.value)
    }

    @Test
    fun currentScreenReflectsWhateverIsWrittenToIt() {
        // App.kt keeps this in sync with the active top-level destination by writing directly to
        // the StateFlow, rather than through a setter method.
        val controller = MenuController()

        controller.currentScreen.value = Screen.Home
        assertEquals(Screen.Home, controller.currentScreen.value)

        controller.currentScreen.value = Screen.Setup
        assertEquals(Screen.Setup, controller.currentScreen.value)
    }

    @Test
    fun searchFieldFocusedReflectsWhateverIsWrittenToIt() {
        // HomeScreen mirrors its local searchFieldFocused state here the same way.
        val controller = MenuController()

        controller.searchFieldFocused.value = true
        assertTrue(controller.searchFieldFocused.value)

        controller.searchFieldFocused.value = false
        assertFalse(controller.searchFieldFocused.value)
    }

    @Test
    fun sendDeliversTheCommandToAnActiveCollector() = runTest {
        val controller = MenuController()
        val received = mutableListOf<MenuCommand>()
        val job = launch { controller.commands.collect { received.add(it) } }
        runCurrent()

        controller.send(MenuCommand.RenameFeed)
        runCurrent()

        assertEquals(listOf(MenuCommand.RenameFeed), received)
        job.cancel()
    }

    @Test
    fun sendDeliversEachCommandExactlyOnceAndInOrder() = runTest {
        val controller = MenuController()
        val received = mutableListOf<MenuCommand>()
        val job = launch { controller.commands.collect { received.add(it) } }
        runCurrent()

        controller.send(MenuCommand.AddFeed)
        controller.send(MenuCommand.RenameFeed)
        controller.send(MenuCommand.UnsubscribeFeed)
        runCurrent()

        assertEquals(
            listOf(MenuCommand.AddFeed, MenuCommand.RenameFeed, MenuCommand.UnsubscribeFeed),
            received,
        )
        job.cancel()
    }

    @Test
    fun everyCollectorReceivesTheSameCommand() = runTest {
        // commands is a broadcast SharedFlow, not a queue split across collectors — e.g. App and
        // FeedListPane can both be watching it at once and must each see every command.
        val controller = MenuController()
        val first = mutableListOf<MenuCommand>()
        val second = mutableListOf<MenuCommand>()
        val firstJob = launch { controller.commands.collect { first.add(it) } }
        val secondJob = launch { controller.commands.collect { second.add(it) } }
        runCurrent()

        controller.send(MenuCommand.UnsubscribeFeed)
        runCurrent()

        assertEquals(listOf(MenuCommand.UnsubscribeFeed), first)
        assertEquals(listOf(MenuCommand.UnsubscribeFeed), second)
        firstJob.cancel()
        secondJob.cancel()
    }

    @Test
    fun sendingWithNoCollectorDoesNotThrow() {
        // A command sent before any screen has started collecting (e.g. a very early menu click)
        // must not crash the menu bar's click handler.
        val controller = MenuController()

        controller.send(MenuCommand.About)
    }

    @Test
    fun separateInstancesDoNotShareState() {
        // App-scoped Koin singleton in production, but nothing about the class itself enforces
        // that — each instance owns its own state.
        val a = MenuController()
        val b = MenuController()

        a.currentScreen.value = Screen.Home
        a.searchFieldFocused.value = true

        assertEquals(Screen.Setup, b.currentScreen.value)
        assertFalse(b.searchFieldFocused.value)
    }
}