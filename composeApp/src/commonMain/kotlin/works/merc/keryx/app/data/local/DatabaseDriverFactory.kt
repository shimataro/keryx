package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.SqlDriver

/** Creates the platform SQL driver for the local `keryx.db`. */
expect class DatabaseDriverFactory() {
    fun create(): SqlDriver
}
