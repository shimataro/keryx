package works.merc.keryx.app.platform

import works.merc.keryx.app.core.DB_FILE_NAME

actual fun databaseFilePath(): String = FileIO.join(AppDirs.appDataDir(), DB_FILE_NAME)
