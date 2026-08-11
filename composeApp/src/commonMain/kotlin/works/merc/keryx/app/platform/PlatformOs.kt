package works.merc.keryx.app.platform

/**
 * Whether the app is running on macOS. Used by common UI code that needs to follow a platform
 * convention (e.g. `Return` vs. `F2` for rename), not just by desktop-only integrations.
 */
expect val isMacOs: Boolean
