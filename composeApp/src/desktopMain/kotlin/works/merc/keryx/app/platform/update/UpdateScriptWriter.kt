package works.merc.keryx.app.platform.update

import works.merc.keryx.app.core.APP_NAME

/**
 * Generates the detached helper scripts an in-app update's self-replace/install hands off to
 * before exiting — pure string templates, deliberately taking no file paths as text to interpolate
 * (every path is instead passed as a separate process argument at launch time by
 * [DesktopUpdateInstaller], so a space or quote in a real path — e.g. a user's own home directory
 * name — never needs shell-escaping in the first place). This is also exactly what makes these
 * templates testable by asserting their constant text directly, without ever actually running one.
 *
 * Every script follows the same shape: wait for [PID] to exit, retreat the current install aside
 * (never delete-then-move — see each function's own KDoc for why), swap the new one in, verify it,
 * and roll back to the retreated copy on any failure along the way.
 */
internal object UpdateScriptWriter {

    /**
     * macOS self-replace. Invoked as `sh script.sh <pid> <app-path> <new-app-path> <old-app-path>
     * <log-path>`.
     *
     * Retreats the running `.app` aside (`mv`, a same-volume rename) rather than deleting it
     * outright first — if the following move of the new bundle into place then fails, the retreated
     * copy is simply moved back, so a mid-swap crash never leaves the install directory empty.
     * `xattr -dr` is a defensive no-op belt: an update this app downloaded itself was never marked
     * quarantined by LaunchServices in the first place (see the design doc), but stripping it anyway
     * costs nothing if some other mechanism ever did.
     */
    fun macSelfReplace(): String = """
        #!/bin/sh
        set -u
        PID=${'$'}1; APP=${'$'}2; NEW=${'$'}3; OLD=${'$'}4; LOG=${'$'}5
        exec >>"${'$'}LOG" 2>&1
        i=0
        while kill -0 "${'$'}PID" 2>/dev/null; do
          i=${'$'}((i+1))
          if [ "${'$'}i" -gt 300 ]; then echo "Timed out waiting for the running app to exit"; exit 10; fi
          sleep 0.1
        done
        sleep 1
        if ! mv "${'$'}APP" "${'$'}OLD"; then echo "Could not move the running app aside"; exit 11; fi
        if ! mv "${'$'}NEW" "${'$'}APP"; then
          echo "Could not move the new app into place; rolling back"
          mv "${'$'}OLD" "${'$'}APP"
          exit 12
        fi
        xattr -dr com.apple.quarantine "${'$'}APP" 2>/dev/null || true
        if [ ! -x "${'$'}APP/Contents/MacOS/$APP_NAME" ]; then
          echo "New app failed its own health check; rolling back"
          rm -rf "${'$'}APP"
          mv "${'$'}OLD" "${'$'}APP"
          exit 13
        fi
        rm -rf "${'$'}OLD"
        open -n -a "${'$'}APP"
    """.trimIndent()

    /**
     * macOS/Linux portable-ZIP self-replace. Invoked as `sh script.sh <pid> <app-dir> <new-dir>
     * <old-dir> <log-path>`. Structurally the same retreat/swap/verify/rollback shape as
     * [macSelfReplace], but checking for `<app-dir>/bin/$APP_NAME` (the portable app-image layout)
     * and relaunching that instead of `open`.
     */
    fun linuxSelfReplace(): String = """
        #!/bin/sh
        set -u
        PID=${'$'}1; APP=${'$'}2; NEW=${'$'}3; OLD=${'$'}4; LOG=${'$'}5
        exec >>"${'$'}LOG" 2>&1
        i=0
        while kill -0 "${'$'}PID" 2>/dev/null; do
          i=${'$'}((i+1))
          if [ "${'$'}i" -gt 300 ]; then echo "Timed out waiting for the running app to exit"; exit 10; fi
          sleep 0.1
        done
        sleep 1
        if ! mv "${'$'}APP" "${'$'}OLD"; then echo "Could not move the running app aside"; exit 11; fi
        if ! mv "${'$'}NEW" "${'$'}APP"; then
          echo "Could not move the new app into place; rolling back"
          mv "${'$'}OLD" "${'$'}APP"
          exit 12
        fi
        if [ ! -x "${'$'}APP/bin/$APP_NAME" ]; then
          echo "New app failed its own health check; rolling back"
          rm -rf "${'$'}APP"
          mv "${'$'}OLD" "${'$'}APP"
          exit 13
        fi
        rm -rf "${'$'}OLD"
        setsid "${'$'}APP/bin/$APP_NAME" >/dev/null 2>&1 &
    """.trimIndent()

    /**
     * Windows portable-ZIP self-replace (`.cmd`). Invoked as `apply.cmd <pid> <app-dir> <new-dir>
     * <old-dir>`.
     *
     * `move` (not `del`/`xcopy`) for the same retreat-before-swap reason as the Unix scripts, and a
     * retry loop around the first move: a `.dll`/`.exe` an antivirus scanner (or a slow-to-release
     * OS handle) still has open for a moment after the process exits fails a rename with "access is
     * denied" rather than blocking, so the script retries a few times before giving up.
     */
    fun windowsSelfReplace(): String = """
        @echo off
        setlocal
        set PID=%~1
        set APP=%~2
        set NEW=%~3
        set OLD=%~4

        :wait
        tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
        if not errorlevel 1 (
          timeout /t 1 /nobreak >nul
          goto wait
        )

        set RETRIES=0
        :move_aside
        move "%APP%" "%OLD%" >nul 2>nul
        if errorlevel 1 (
          set /a RETRIES+=1
          if %RETRIES% geq 10 (
            echo Could not move the running app aside
            exit /b 11
          )
          timeout /t 1 /nobreak >nul
          goto move_aside
        )

        move "%NEW%" "%APP%" >nul 2>nul
        if errorlevel 1 (
          echo Could not move the new app into place; rolling back
          move "%OLD%" "%APP%" >nul 2>nul
          exit /b 12
        )

        if not exist "%APP%\$APP_NAME.exe" (
          echo New app failed its own health check; rolling back
          rmdir /s /q "%APP%"
          move "%OLD%" "%APP%" >nul 2>nul
          exit /b 13
        )

        rmdir /s /q "%OLD%"
        start "" "%APP%\$APP_NAME.exe"
        endlocal
    """.trimIndent()

    /**
     * Windows MSI upgrade install (`.cmd`). Invoked as `apply.cmd <pid> <msi-path> <exe-path>
     * <log-path>`.
     *
     * `upgradeUuid` is fixed in `composeApp/build.gradle.kts`, so `msiexec /i` on a newer
     * `ProductVersion` performs a WiX MajorUpgrade (silently uninstalling the old product first)
     * rather than failing as "already installed". `/passive` still lets Windows show its own UAC
     * elevation prompt — that part cannot be suppressed, and isn't meant to be. If the user declines
     * it (exit code 1602) or the upgrade otherwise fails, the script simply relaunches whatever is
     * still at `<exe-path>` rather than treating that as fatal — falling back to the previous,
     * working install beats leaving the user with nothing running at all.
     */
    fun windowsMsiInstall(): String = """
        @echo off
        setlocal
        set PID=%~1
        set MSI=%~2
        set EXE=%~3
        set LOG=%~4

        :wait
        tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
        if not errorlevel 1 (
          timeout /t 1 /nobreak >nul
          goto wait
        )

        msiexec /i "%MSI%" /passive /norestart /L*v "%LOG%"

        if exist "%EXE%" start "" "%EXE%"
        endlocal
    """.trimIndent()
}
