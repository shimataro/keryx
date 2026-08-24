package works.merc.keryx.app.platform

/**
 * Whether this build should offer its own "check for update" (see
 * [works.merc.keryx.app.domain.checkForUpdateAndNotify] and the Updates settings tab). Always
 * `true` on desktop, since there is no app-store update mechanism to defer to there. On Android,
 * backed by [works.merc.keryx.app.core.isSelfUpdateCheckSupported] fed the app's own installer
 * package name — see that function's KDoc for why this is a UX call, not a store-policy one.
 */
expect val selfUpdateCheckSupported: Boolean

/**
 * DI-friendly wrapper around [selfUpdateCheckSupported], the same way [works.merc.keryx.app.core.Clock]
 * wraps [works.merc.keryx.app.core.SystemClock] — lets [works.merc.keryx.app.domain.checkForUpdateAndNotify]
 * resolve this through Koin instead of reading the platform value directly, so a test can override it.
 */
fun interface SelfUpdateCheckSupport {
    fun isSupported(): Boolean
}
