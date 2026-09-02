package works.merc.keryx.app.platform.update

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Asserts the generated script text itself, per the design doc's own verification plan for this
 * seam ("assert the generated script body as a string, per branch: retreat / place / verify /
 * rollback") — the whole point of [UpdateScriptWriter] being a pure template generator is that an
 * automated test never actually runs a self-replace, so string assertions are the only check that
 * belongs here. [DesktopUpdateInstallerTest] covers the process launch side with a fake
 * `ProcessLauncher`.
 */
class UpdateScriptWriterTest {

    @Test
    fun macSelfReplaceWaitsForThePidBeforeTouchingAnything() {
        val script = UpdateScriptWriter.macSelfReplace()
        val waitIndex = script.indexOf("while kill -0")
        val moveIndex = script.indexOf("mv \"\$APP\" \"\$OLD\"")
        assertTrue(waitIndex >= 0 && moveIndex > waitIndex, "the PID wait loop must precede any file move")
    }

    @Test
    fun macSelfReplaceRetreatsTheRunningAppRatherThanDeletingItFirst() {
        val script = UpdateScriptWriter.macSelfReplace()
        // "retreat then place": the running app is moved aside (mv, never rm) before the new one
        // takes its place, so a failure partway through never leaves the install directory empty.
        assertContains(script, "mv \"\$APP\" \"\$OLD\"")
        assertContains(script, "mv \"\$NEW\" \"\$APP\"")
    }

    @Test
    fun macSelfReplaceRollsBackWhenPlacingTheNewAppFails() {
        val script = UpdateScriptWriter.macSelfReplace()
        val placeIndex = script.indexOf("if ! mv \"\$NEW\" \"\$APP\"")
        val rollbackIndex = script.indexOf("mv \"\$OLD\" \"\$APP\"", startIndex = placeIndex + 1)
        assertTrue(placeIndex >= 0 && rollbackIndex > placeIndex, "a failed placement must roll back to the retreated copy")
        assertContains(script, "exit 12")
    }

    @Test
    fun macSelfReplaceVerifiesTheNewAppBeforeDiscardingTheOldOne() {
        val script = UpdateScriptWriter.macSelfReplace()
        assertContains(script, "-x \"\$APP/Contents/MacOS/Keryx\"")
        val verifyIndex = script.indexOf("-x \"\$APP/Contents/MacOS/Keryx\"")
        val discardIndex = script.indexOf("rm -rf \"\$OLD\"", startIndex = verifyIndex)
        assertTrue(discardIndex > verifyIndex, "the old app must survive until the new one passes its health check")
    }

    @Test
    fun macSelfReplaceStripsQuarantineAsAHarmlessBelt() {
        assertContains(UpdateScriptWriter.macSelfReplace(), "xattr -dr com.apple.quarantine")
    }

    @Test
    fun macSelfReplaceRelaunchesTheNewApp() {
        assertContains(UpdateScriptWriter.macSelfReplace(), "open -n -a \"\$APP\"")
    }

    @Test
    fun linuxSelfReplaceFollowsTheSameRetreatPlaceVerifyRollbackShape() {
        val script = UpdateScriptWriter.linuxSelfReplace()
        assertContains(script, "mv \"\$APP\" \"\$OLD\"")
        assertContains(script, "mv \"\$NEW\" \"\$APP\"")
        assertContains(script, "-x \"\$APP/bin/Keryx\"")
    }

    @Test
    fun linuxSelfReplaceVerifiesBeforeDiscardingTheOldInstall() {
        val script = UpdateScriptWriter.linuxSelfReplace()
        val verifyIndex = script.indexOf("-x \"\$APP/bin/Keryx\"")
        val discardIndex = script.indexOf("rm -rf \"\$OLD\"", startIndex = verifyIndex)
        assertTrue(verifyIndex >= 0 && discardIndex > verifyIndex)
    }

    @Test
    fun linuxSelfReplaceRelaunchesTheNewAppDetached() {
        assertContains(UpdateScriptWriter.linuxSelfReplace(), "setsid \"\$APP/bin/Keryx\"")
    }

    @Test
    fun windowsSelfReplaceRetriesTheFirstMoveBeforeGivingUp() {
        val script = UpdateScriptWriter.windowsSelfReplace()
        assertContains(script, ":move_aside")
        assertContains(script, "RETRIES")
        assertContains(script, "geq 10")
    }

    @Test
    fun windowsSelfReplaceRollsBackWhenPlacingTheNewAppFails() {
        val script = UpdateScriptWriter.windowsSelfReplace()
        val placeIndex = script.indexOf("move \"%NEW%\" \"%APP%\"")
        val rollbackIndex = script.indexOf("move \"%OLD%\" \"%APP%\"", startIndex = placeIndex + 1)
        assertTrue(placeIndex >= 0 && rollbackIndex > placeIndex)
        assertContains(script, "exit /b 12")
    }

    @Test
    fun windowsSelfReplaceVerifiesBeforeDiscardingTheOldInstall() {
        val script = UpdateScriptWriter.windowsSelfReplace()
        val verifyIndex = script.indexOf("if not exist \"%APP%\\Keryx.exe\"")
        val discardIndex = script.indexOf("rmdir /s /q \"%OLD%\"", startIndex = verifyIndex)
        assertTrue(verifyIndex >= 0 && discardIndex > verifyIndex)
    }

    @Test
    fun windowsSelfReplaceRelaunchesTheNewExe() {
        assertContains(UpdateScriptWriter.windowsSelfReplace(), "start \"\" \"%APP%\\Keryx.exe\"")
    }

    @Test
    fun windowsMsiInstallWaitsForThePidThenRunsMsiexecPassively() {
        val script = UpdateScriptWriter.windowsMsiInstall()
        val waitIndex = script.indexOf(":wait")
        val msiexecIndex = script.indexOf("msiexec /i")
        assertTrue(waitIndex >= 0 && msiexecIndex > waitIndex)
        assertContains(script, "/passive")
        assertContains(script, "/norestart")
    }

    @Test
    fun windowsMsiInstallFallsBackToRelaunchingWhicheverExeEndsUpInPlace() {
        // Deliberately not conditioned on msiexec's own exit code: a declined UAC prompt or a
        // failed upgrade should still relaunch the previous, still-working install rather than
        // leaving the user with nothing running (see the function's own KDoc).
        assertContains(UpdateScriptWriter.windowsMsiInstall(), "if exist \"%EXE%\" start \"\" \"%EXE%\"")
    }
}
