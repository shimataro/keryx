package works.merc.keryx.app.presentation.home

/**
 * The physical keys the home screen's keyboard shortcuts are defined over. Each UI maps its own key
 * events onto these (anything else is [Other]), so the shortcut table itself — [homeShortcutFor] —
 * is written once. See `external-spec.md` §9 for the user-facing list.
 */
enum class HomeKey { Escape, Up, Down, Left, Right, PageUp, PageDown, Space, Home, End, J, K, F, R, F2, Enter, Delete, Backspace, Other }

/** Which modifier keys were held with a [HomeKey]. */
data class KeyModifiers(val shift: Boolean = false, val ctrl: Boolean = false, val meta: Boolean = false)

/** What a home-screen shortcut does; the UI binds each to its own action. */
enum class HomeShortcut {
    Escape, Up, Down, Left, Right, PageUp, PageDown, Home, End,
    NextArticle, PreviousArticle, RenameFeedListItem, DeleteFeedListItem, Search, RefreshList,
}

/**
 * The bare key each OS's own file manager uses to start a rename (Finder: Return,
 * Explorer/Nautilus/Dolphin: F2) — the home screen's rename shortcut, and the native application
 * menu's rename accelerator.
 */
fun renameHomeKey(isMacOs: Boolean): HomeKey = if (isMacOs) HomeKey.Enter else HomeKey.F2

/**
 * The shortcut [key] + [modifiers] triggers on the home screen, or `null` for none.
 *
 * While [textInputFocused] (the search field, or a row's inline name editor) only Escape and ↓/↑
 * are shortcuts: a single-line field has no caret use for ↓/↑, so they let the result list be
 * browsed without leaving the field, while every letter and ←/→ stay with the field itself.
 * [refreshListAvailable] gates Ctrl+Shift+R (see `external-spec.md` §9's pull-to-refresh). Space
 * pages down, Shift+Space up; J/K and the rename/delete keys only fire without Ctrl/Cmd, so they
 * never shadow an application-menu accelerator.
 */
fun homeShortcutFor(
    key: HomeKey,
    modifiers: KeyModifiers,
    textInputFocused: Boolean,
    refreshListAvailable: Boolean,
    isMacOs: Boolean,
): HomeShortcut? {
    if (key == HomeKey.Escape) return HomeShortcut.Escape
    if (textInputFocused) {
        return when (key) {
            HomeKey.Down -> HomeShortcut.Down
            HomeKey.Up -> HomeShortcut.Up
            else -> null
        }
    }
    val bare = !modifiers.ctrl && !modifiers.meta
    return when {
        (modifiers.meta || modifiers.ctrl) && key == HomeKey.F -> HomeShortcut.Search
        refreshListAvailable && modifiers.ctrl && modifiers.shift && !modifiers.meta && key == HomeKey.R -> HomeShortcut.RefreshList
        key == HomeKey.Down -> HomeShortcut.Down
        key == HomeKey.Up -> HomeShortcut.Up
        key == HomeKey.Left -> HomeShortcut.Left
        key == HomeKey.Right -> HomeShortcut.Right
        key == HomeKey.PageUp -> HomeShortcut.PageUp
        key == HomeKey.PageDown -> HomeShortcut.PageDown
        key == HomeKey.Space -> if (modifiers.shift) HomeShortcut.PageUp else HomeShortcut.PageDown
        key == HomeKey.Home -> HomeShortcut.Home
        key == HomeKey.End -> HomeShortcut.End
        bare && key == HomeKey.J -> HomeShortcut.NextArticle
        bare && key == HomeKey.K -> HomeShortcut.PreviousArticle
        bare && key == renameHomeKey(isMacOs) -> HomeShortcut.RenameFeedListItem
        bare && (key == HomeKey.Delete || key == HomeKey.Backspace) -> HomeShortcut.DeleteFeedListItem
        else -> null
    }
}
