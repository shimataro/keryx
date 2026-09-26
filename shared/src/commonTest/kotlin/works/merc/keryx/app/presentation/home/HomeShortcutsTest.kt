package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeShortcutsTest {

    private fun shortcut(
        key: HomeKey,
        modifiers: KeyModifiers = KeyModifiers(),
        textInputFocused: Boolean = false,
        refreshListAvailable: Boolean = false,
        isMacOs: Boolean = false,
    ) = homeShortcutFor(key, modifiers, textInputFocused, refreshListAvailable, isMacOs)

    @Test
    fun navigationKeysMapToTheirShortcuts() {
        assertEquals(HomeShortcut.Down, shortcut(HomeKey.Down))
        assertEquals(HomeShortcut.Up, shortcut(HomeKey.Up))
        assertEquals(HomeShortcut.Left, shortcut(HomeKey.Left))
        assertEquals(HomeShortcut.Right, shortcut(HomeKey.Right))
        assertEquals(HomeShortcut.PageUp, shortcut(HomeKey.PageUp))
        assertEquals(HomeShortcut.PageDown, shortcut(HomeKey.PageDown))
        assertEquals(HomeShortcut.Home, shortcut(HomeKey.Home))
        assertEquals(HomeShortcut.End, shortcut(HomeKey.End))
    }

    @Test
    fun spacePagesDownAndShiftSpacePagesUp() {
        assertEquals(HomeShortcut.PageDown, shortcut(HomeKey.Space))
        assertEquals(HomeShortcut.PageUp, shortcut(HomeKey.Space, KeyModifiers(shift = true)))
    }

    @Test
    fun jAndKStepThroughArticlesOnlyWithoutCtrlOrCmd() {
        assertEquals(HomeShortcut.NextArticle, shortcut(HomeKey.J))
        assertEquals(HomeShortcut.PreviousArticle, shortcut(HomeKey.K))
        assertNull(shortcut(HomeKey.J, KeyModifiers(meta = true)))
        assertNull(shortcut(HomeKey.K, KeyModifiers(ctrl = true)))
    }

    @Test
    fun renameFollowsEachOsFileManager() {
        assertEquals(HomeShortcut.RenameFeedListItem, shortcut(HomeKey.Enter, isMacOs = true))
        assertNull(shortcut(HomeKey.F2, isMacOs = true))
        assertEquals(HomeShortcut.RenameFeedListItem, shortcut(HomeKey.F2, isMacOs = false))
        assertNull(shortcut(HomeKey.Enter, isMacOs = false))
    }

    @Test
    fun deleteAndBackspaceBothDelete() {
        assertEquals(HomeShortcut.DeleteFeedListItem, shortcut(HomeKey.Delete))
        assertEquals(HomeShortcut.DeleteFeedListItem, shortcut(HomeKey.Backspace))
        assertNull(shortcut(HomeKey.Backspace, KeyModifiers(meta = true)))
    }

    @Test
    fun cmdOrCtrlFSearches() {
        assertEquals(HomeShortcut.Search, shortcut(HomeKey.F, KeyModifiers(meta = true)))
        assertEquals(HomeShortcut.Search, shortcut(HomeKey.F, KeyModifiers(ctrl = true)))
        assertNull(shortcut(HomeKey.F))
    }

    @Test
    fun ctrlShiftRRefreshesOnlyWhereAvailable() {
        val ctrlShift = KeyModifiers(ctrl = true, shift = true)
        assertEquals(HomeShortcut.RefreshList, shortcut(HomeKey.R, ctrlShift, refreshListAvailable = true))
        assertNull(shortcut(HomeKey.R, ctrlShift, refreshListAvailable = false))
        assertNull(shortcut(HomeKey.R, KeyModifiers(ctrl = true, shift = true, meta = true), refreshListAvailable = true))
    }

    @Test
    fun aFocusedTextFieldKeepsEverythingButEscapeAndVerticalArrows() {
        assertEquals(HomeShortcut.Escape, shortcut(HomeKey.Escape, textInputFocused = true))
        assertEquals(HomeShortcut.Down, shortcut(HomeKey.Down, textInputFocused = true))
        assertEquals(HomeShortcut.Up, shortcut(HomeKey.Up, textInputFocused = true))
        assertNull(shortcut(HomeKey.Left, textInputFocused = true))
        assertNull(shortcut(HomeKey.J, textInputFocused = true))
        assertNull(shortcut(HomeKey.F, KeyModifiers(meta = true), textInputFocused = true))
        assertNull(shortcut(HomeKey.Backspace, textInputFocused = true))
    }

    @Test
    fun otherKeysAreNotShortcuts() {
        assertNull(shortcut(HomeKey.Other))
    }
}
