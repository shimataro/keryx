package works.merc.keryx.app.platform.update

import works.merc.keryx.app.core.Log
import java.io.IOException

private const val TAG = "CodeSigningVerifier"
private const val VERIFY_TIMEOUT_SECONDS = 30L

/**
 * Seam over `codesign --verify` on a freshly extracted macOS `.app` bundle, checked by
 * [DesktopUpdateInstaller.verifyExtractedApp] before the self-replace swap. Faking this in tests is
 * the whole point: [DesktopUpdateInstallerTest]'s synthetic `macAppZip(...)` fixtures aren't
 * actually signed, so a real `codesign` invocation would always fail against them.
 *
 * This is deliberately a **self-consistency** check only — it confirms the extracted bundle's
 * signature is internally intact (every sealed resource matches its recorded hash), not that the
 * signature belongs to a trusted publisher. A full identity check (`codesign --verify -R
 * "anchor apple generic and certificate leaf[subject.OU] = <team id>"`, or simply `--verify
 * --strict` against a bundle actually signed by a Developer ID certificate and notarized) can't be
 * required yet: current release builds are signed ad-hoc (see
 * [UpdateScriptWriter.macSelfReplace]'s own KDoc on why `xattr -dr` is still needed for the same
 * reason), and an ad-hoc signature has no certificate chain for `-R` to check — every extracted
 * update would fail a Developer-ID check unconditionally. Once releases are signed with a real
 * Developer ID and notarized, this should be tightened to a publisher check; see `SECURITY.md`.
 */
internal fun interface CodeSigningVerifier {
    /**
     * Checks the app bundle at [appPath] against `codesign`'s own signature self-check.
     *
     * @return `null` when it passes, otherwise why not — a reason rather than a `Boolean` so
     *   "the signature is bad" stays distinguishable from "the check could not be completed".
     *   That distinction reaches the user's log through
     *   [works.merc.keryx.app.domain.UpdateRepository], where reporting a cancelled or wedged check
     *   as a *corrupt bundle* would point the next investigation at the packaging pipeline instead
     *   of at the timeout.
     */
    fun verify(appPath: String): String?
}

/**
 * Real verifier backed by `codesign --verify --strict --deep`: `--strict` catches structural
 * issues (e.g. extra unsigned files) a plain `--verify` misses, and `--deep` recurses into nested
 * code (frameworks, helper tools) rather than checking only the outer bundle. Runs synchronously —
 * this is a single local process invocation on a bundle that was just extracted to disk, not a
 * network call — and treats anything other than a clean exit (missing `codesign` binary, timeout,
 * non-zero exit) as a failed verification rather than propagating the process-launch failure
 * itself, since either way the answer [DesktopUpdateInstaller] needs is the same: don't swap it in.
 */
internal class RealCodeSigningVerifier : CodeSigningVerifier {
    override fun verify(appPath: String): String? = try {
        // Absolute path for the same reason DittoArchiveExtractor uses one.
        val command = listOf("/usr/bin/codesign", "--verify", "--strict", "--deep", appPath)
        when (val result = runLocalProcess(command, VERIFY_TIMEOUT_SECONDS, TAG)) {
            is LocalProcessResult.Exited ->
                if (result.code == 0) null else "Extracted app failed its own code-signature self-check (codesign exit ${result.code})"
            LocalProcessResult.TimedOut ->
                "Could not complete the code-signature check: codesign timed out after $VERIFY_TIMEOUT_SECONDS seconds"
            LocalProcessResult.Interrupted ->
                "Could not complete the code-signature check: interrupted"
        }
    } catch (e: IOException) {
        Log.error(TAG, "Failed to run codesign --verify for $appPath", e)
        "Could not run the code-signature check: ${e.message}"
    }
}
