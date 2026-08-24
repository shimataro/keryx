package works.merc.keryx.app.platform

import works.merc.keryx.app.core.DB_FILE_NAME

/**
 * Same file `AndroidSqliteDriver` opens: `Context.getDatabasePath` resolves to
 * `<dataDir>/databases/keryx.db`, a different directory than [AppDirs.appDataDir]
 * (`Context.filesDir`, i.e. `<dataDir>/files`).
 */
actual fun databaseFilePath(): String = AndroidAppContext.application.getDatabasePath(DB_FILE_NAME).absolutePath
