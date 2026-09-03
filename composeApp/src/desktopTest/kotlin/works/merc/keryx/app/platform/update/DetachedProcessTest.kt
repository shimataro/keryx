package works.merc.keryx.app.platform.update

import works.merc.keryx.app.platform.isWindows
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [RealProcessLauncher] itself, not just the [ProcessLauncher] seam other tests fake —
 * this class had zero coverage before a real bug slipped through it:
 * `.redirectInput(ProcessBuilder.Redirect.DISCARD)` throws `IllegalArgumentException` on every
 * call (`DISCARD`'s `type()` is `WRITE`, and `redirectInput` only accepts a source of input), so
 * every self-replace install launch failed before `.start()` was ever reached — silently, since
 * that exception isn't an `IOException` and escaped every `catch` on the way up. [launch] only
 * promises whether the OS accepted the exec (see its own KDoc); it never returns the launched
 * [Process], so a real process is the only way to confirm one actually ran.
 */
class DetachedProcessTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(prefix: String): File = createTempDirectory(prefix).toFile().also { tempDirs.add(it) }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /**
     * Launches the very JVM this test is running under, with an argument that makes it exit
     * immediately. Deliberately avoids a shell (`sh -c`/`cmd /c`) and any path that would need
     * quoting — a temp directory can contain spaces, and getting that wrong would test the shell's
     * parsing, not [RealProcessLauncher]. This alone would already have caught the real bug: the
     * `IllegalArgumentException` above is thrown before `ProcessBuilder.start()` is reached at all,
     * regardless of which command is given.
     */
    @Test
    fun launchingAValidCommandSucceedsOnEveryPlatform() {
        val javaHome = System.getProperty("java.home")
        val javaBinary = File(javaHome, if (isWindows) "bin/java.exe" else "bin/java")
        check(javaBinary.isFile) { "Expected to find the running JVM's own launcher at $javaBinary" }

        val launched = RealProcessLauncher().launch(listOf(javaBinary.path, "-version"))

        assertTrue(launched, "launching the current JVM's own binary must succeed")
    }

    /**
     * Confirms a launched process is genuinely detached and running, not just that `start()`
     * didn't throw — POSIX-only, since `/usr/bin/touch` isn't portable to Windows and the
     * production self-replace/msiexec scripts this launcher exists for only run on macOS/Linux
     * self-replace and Windows portable/MSI paths alike, all equally exercised by the test above.
     */
    @Test
    fun runsTheProcessDetached() {
        if (isWindows) return // see this test's own KDoc

        val marker = File(newTempDir("detached-process-test"), "touched")
        val launched = RealProcessLauncher().launch(listOf("/usr/bin/touch", marker.path))

        assertTrue(launched)
        val deadline = System.currentTimeMillis() + 5_000
        while (!marker.exists()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $marker to appear" }
            Thread.sleep(20)
        }
    }

    @Test
    fun reportsFailureRatherThanThrowingForACommandThatCannotStart() {
        val nonExistent = File(newTempDir("detached-process-test-missing"), "does-not-exist").path

        val launched = RealProcessLauncher().launch(listOf(nonExistent))

        assertFalse(launched)
    }
}
