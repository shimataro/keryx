package works.merc.keryx.app.platform

/**
 * Compose Multiplatform's AWT accessibility bridge (`ComposeAccessible`, checked in 1.11.1) builds a
 * node's accessible name from its `ContentDescription`, falling back to its `Text`, and never reads
 * `StateDescription`. Flip this to `true` once the bridge exports it.
 */
internal actual val accessibilityBridgeReadsStateDescription: Boolean = false
