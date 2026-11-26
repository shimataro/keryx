package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver

/**
 * The one way a re-key is recorded in `ks_id_remap`: the statements that add a batch of moves
 * `(kind, old_id, new_id)` and fold every chain they extend, so the table always maps an old id
 * straight to the id its row has now and a lookup needs one hop. Kinds: [ID_REMAP_KIND_TAG] and
 * [ID_REMAP_KIND_FOLDER] ([V2IdentitySql], the v2->v3 re-key), [ID_REMAP_KIND_FEED] and
 * [ID_REMAP_KIND_ARTICLE] ([FeedIdentitySql]); chains are folded within a kind only.
 *
 * For each move `old -> new` of a batch, in this order:
 * 1. an entry for `new` itself is dropped — `new` names a live row again, and a lookup must not move
 *    it on (this is also what keeps a move back, `new -> old` followed by `old -> new`, from leaving a
 *    cycle);
 * 2. every entry that ended at `old` is redirected to `new` (`a -> old` becomes `a -> new`);
 * 3. `old -> new` itself is inserted (replacing an earlier entry for `old`).
 * A move onto itself is ignored. No step can produce an entry mapping an id onto itself.
 *
 * Statements are plain SQL over a source of moves, so the same text runs on the local driver
 * ([record], the v2->v3 migration and tests) and on a merge connection, where the moves come from a
 * table the merge filled — as `MergeSql`'s statements do. They name `main.ks_id_remap`, so a pre-delta
 * cloud file attached as `cloud` (which may carry a table of the same name) is never touched.
 */
object IdRemapSql {

    /**
     * The statements that record the moves [source] yields, in order. [source] is a query producing
     * three columns in the order kind, old id, new id — a `SELECT` over a table, or `VALUES (?, ?, ?)`
     * for one move bound per statement ([record]).
     *
     * Precondition for a batch of several moves: it is already folded in itself — no move's new id is
     * another move's old id, and no two moves share an old id — as a batch mapping each moved id
     * straight to its final one is. The statements apply the whole batch set-wise, so a chain inside
     * the batch would be folded only one step.
     */
    fun foldStatements(source: String): List<String> {
        val moves = "WITH ks_remap_src(kind, old_id, new_id) AS ($source), " +
            "ks_remap_moves AS (SELECT kind, old_id, new_id FROM ks_remap_src WHERE old_id <> new_id) "
        return listOf(
            moves + "DELETE FROM main.ks_id_remap WHERE EXISTS (SELECT 1 FROM ks_remap_moves m " +
                "WHERE m.kind = ks_id_remap.kind AND m.new_id = ks_id_remap.old_id)",
            moves + "UPDATE main.ks_id_remap SET new_id = (SELECT m.new_id FROM ks_remap_moves m " +
                "WHERE m.kind = ks_id_remap.kind AND m.old_id = ks_id_remap.new_id) " +
                "WHERE EXISTS (SELECT 1 FROM ks_remap_moves m " +
                "WHERE m.kind = ks_id_remap.kind AND m.old_id = ks_id_remap.new_id)",
            moves + "INSERT OR REPLACE INTO main.ks_id_remap (kind, old_id, new_id) " +
                "SELECT kind, old_id, new_id FROM ks_remap_moves",
        )
    }

    private const val ONE_MOVE = "VALUES (?, ?, ?)"

    private val oneMoveStatements = foldStatements(ONE_MOVE)

    /**
     * Records one move `oldId -> newId` of [kind] (one of the kinds in the object KDoc) on
     * [driver], folding the chains it extends (see the object KDoc). Run it inside the transaction
     * that moved the row, so the record and the move commit together.
     */
    fun record(driver: SqlDriver, kind: String, oldId: String, newId: String) {
        for (sql in oneMoveStatements) {
            driver.execute(null, sql, 3) {
                bindString(0, kind)
                bindString(1, oldId)
                bindString(2, newId)
            }
        }
    }
}
