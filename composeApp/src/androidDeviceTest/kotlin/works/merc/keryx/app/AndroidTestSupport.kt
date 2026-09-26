package works.merc.keryx.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The app-under-test's own Context. (:shared's device tests keep their own copy alongside their
 * database helpers — a device-test source set cannot be shared across modules.)
 */
internal fun testContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext
