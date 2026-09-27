package works.merc.keryx.app.platform

import works.merc.keryx.app.core.DB_FILE_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the default-path contract [SyncRepository][works.merc.keryx.app.domain.SyncRepository]
 * relies on: its `localDbPath` constructor parameter defaults to [databaseFilePath]. That default used to be
 * inlined as `FileIO.join(AppDirs.appDataDir(), DB_FILE_NAME)` directly in `SyncRepository` — correct on
 * desktop, but silently wrong on Android, where `AndroidSqliteDriver` places the live DB under a different
 * directory (`Context.getDatabasePath`, not `AppDirs.appDataDir()`/`Context.filesDir`) — see [databaseFilePath]'s
 * own KDoc. This only exercises the desktop actual, not `SyncRepository` directly: constructing it without an
 * explicit `localDbPath` would exercise the real `AppDirs.appDataDir()` on whatever machine runs the test,
 * i.e. a developer's actual Keryx data directory, which every other `SyncRepository` test deliberately avoids
 * by always passing an explicit temp-file `localDbPath`.
 */
class DatabaseFileTest {
    @Test
    fun desktopDatabaseFilePathMatchesAppDataDirPlusDbFileName() {
        assertEquals(FileIO.join(AppDirs.appDataDir(), DB_FILE_NAME), databaseFilePath())
    }
}
