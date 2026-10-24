package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppForegroundTest {
    @Test
    fun startsNotInFront() {
        assertFalse(AppForeground().inForeground.value)
    }

    @Test
    fun setIsReflectedInTheFlow() {
        val foreground = AppForeground()

        foreground.set(true)
        assertTrue(foreground.inForeground.value)

        foreground.set(false)
        assertFalse(foreground.inForeground.value)
    }
}
