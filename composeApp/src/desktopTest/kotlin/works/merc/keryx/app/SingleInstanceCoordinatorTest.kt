package works.merc.keryx.app

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceCoordinatorTest {
    private lateinit var appDataDir: File
    private lateinit var first: SingleInstanceCoordinator
    private lateinit var second: SingleInstanceCoordinator

    @BeforeTest
    fun setUp() {
        appDataDir = createTempDirectory("single-instance-coordinator-test").toFile()
        first = SingleInstanceCoordinator(appDataDir)
        second = SingleInstanceCoordinator(appDataDir)
    }

    @AfterTest
    fun tearDown() {
        first.close()
        second.close()
    }

    @Test
    fun secondInstanceFailsToAcquireLockHeldByFirst() {
        assertTrue(first.tryAcquireLock())
        assertFalse(second.tryAcquireLock())
    }

    @Test
    fun signalRunningInstanceInvokesActivationCallbackOnListener() = runBlocking {
        assertTrue(first.tryAcquireLock())

        val activated = AtomicBoolean(false)
        first.startActivationListener { activated.set(true) }

        assertTrue(second.signalRunningInstance())

        val deadline = System.currentTimeMillis() + 5_000
        while (!activated.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(activated.get())
    }

    @Test
    fun signalRunningInstanceFailsWithoutAListener() {
        assertFalse(second.signalRunningInstance())
    }

    @Test
    fun signalRunningInstanceForwardsUriString() = runBlocking {
        assertTrue(first.tryAcquireLock())

        var receivedUri: String? = "placeholder"
        first.startActivationListener { uri -> receivedUri = uri }

        val testUri = "keryx://oauth2/callback?code=abc&state=xyz"
        assertTrue(second.signalRunningInstance(testUri))

        val deadline = System.currentTimeMillis() + 5_000
        while (receivedUri == "placeholder" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(testUri, receivedUri)
    }
}
