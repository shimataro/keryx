package works.merc.keryx.app.core

import kotlin.time.ExperimentalTime

/**
 * Abstraction over "current time" so repositories can be tested with a fixed
 * clock. Production code uses [SystemClock].
 */
fun interface Clock {
    fun nowMillis(): Long
}

@OptIn(ExperimentalTime::class)
object SystemClock : Clock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
