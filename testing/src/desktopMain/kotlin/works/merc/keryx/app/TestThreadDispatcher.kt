package works.merc.keryx.app

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * A dispatcher backed by one thread of its own, for code under test that needs a real thread while
 * the test itself blocks waiting on it (e.g. [awaitConditionBlocking]). Unlike `Dispatchers.Default`,
 * it is shared with nothing else in the test JVM, so work an earlier test left running cannot delay
 * it. The thread is a daemon, but [ExecutorCoroutineDispatcher.close] it once the test is done.
 */
fun testThreadDispatcher(name: String): ExecutorCoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name).apply { isDaemon = true } }
        .asCoroutineDispatcher()
