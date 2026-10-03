package works.merc.keryx.app.platform

/**
 * The app's currently resolved light/dark appearance, published process-wide for Android code
 * that runs outside composition and still has to match the in-app theme setting rather than the
 * system's (e.g. browser chrome launched from [BrowserOpener]).
 *
 * Written only by `:composeApp`'s Android `ProvidePlatformInteraction` (`ui/theme/`), from the
 * same `dark` flag `KeryxTheme` resolves for the whole UI, so it never disagrees with what is on
 * screen. Before the first composition it holds the default `false` (light); readers outside
 * composition only run after the UI is up, so that default is never observed in practice.
 */
object AndroidAppearance {
    /** `true` while the app's UI is drawn in its dark theme. */
    @Volatile
    var isDark: Boolean = false
}
