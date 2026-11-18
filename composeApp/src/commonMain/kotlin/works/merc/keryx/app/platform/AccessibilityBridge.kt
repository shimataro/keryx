package works.merc.keryx.app.platform

/**
 * Whether the platform's accessibility bridge passes a semantics node's `stateDescription` on to the
 * screen reader. Where it does not, a state that matters (the notification center row's alert level)
 * also has to go into the node's accessible name.
 */
internal expect val accessibilityBridgeReadsStateDescription: Boolean
