package works.merc.keryx.app.core

/**
 * Why a local DB write the app made off the UI thread failed, reduced to what the user can do about
 * it — the category the notification for that failure names (see `docs/error-design.md`, "Local
 * write failures"). Decided from the SQLite result code behind the failure
 * (`platform/LocalWriteFailure.kt`'s `localWriteFailureKind`), never from message text.
 */
enum class LocalWriteFailureKind {
    /** Another connection held the database (`SQLITE_BUSY` / `SQLITE_LOCKED`): trying again can succeed. */
    BUSY,

    /** The disk is full (`SQLITE_FULL`): space has to be freed first. */
    DISK_FULL,

    /**
     * Anything else — an I/O error, a read-only or corrupt database, a file that could not be
     * opened, or a failure with no SQLite result code behind it at all: restarting the app is the
     * one next step left.
     */
    OTHER,
}
