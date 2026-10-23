package works.merc.keryx.app

/**
 * Registers [block] to run when the JVM shuts down. A failure inside it is swallowed: a shutdown
 * hook has nobody left to report to, and an escaping exception would only cut the other hooks short.
 */
internal fun addShutdownHook(block: () -> Unit) {
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { block() } })
}
