package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import works.merc.keryx.app.core.searchTerms

/**
 * A single full-text search hit: the matching article id plus its title with the matched terms
 * wrapped in [MARK_START]/[MARK_END] sentinels (see [FtsSearch]). The UI turns those sentinels into
 * highlighted spans (bold + marker background); they never reach the screen as literal characters.
 */
data class FtsHit(
    val id: String,
    val titleMarked: String,
)

/**
 * Runs full-text search against `articles_fts` and returns matching articles in rank order, each
 * with FTS5-provided highlight (title) markup. The body (`search_text`) is still MATCHed so
 * body-only hits are returned, but no snippet is projected. The caller loads full rows via the
 * generated queries; the markup here is only used to highlight matched terms (bold + marker).
 */
class FtsSearch(private val driver: SqlDriver) {

    /**
     * Searches articles using all terms in the query and returns matching active articles
     * in relevance order, with matched title terms marked for highlighting.
     *
     * @param rawQuery The user-entered search query.
     * @return The matching article identifiers and marked titles.
     */
    fun search(rawQuery: String): List<FtsHit> {
        val terms = searchTerms(rawQuery)
        if (terms.isEmpty()) return emptyList()
        // Space-separated terms are AND-matched (an article must contain every term). Each term is
        // wrapped as its own quoted FTS5 string so arbitrary user input (quotes, operators like
        // AND/OR/-/*, punctuation) is treated as literal text and can't produce a MATCH syntax
        // error. Embedded quotes are escaped. Order-independent (not a phrase search).
        val matchArg = terms.joinToString(" AND ") { "\"" + it.replace("\"", "\"\"") + "\"" }

        val hits = mutableListOf<FtsHit>()
        driver.executeQuery(
            identifier = null,
            // highlight() takes the FTS table itself as its first arg, referenced by its real name
            // `articles_fts` (an alias is rejected as "no such column"). Column index 0 = title
            // matches the CREATE VIRTUAL TABLE order in FtsManager. The marker chars (STX/ETX) are
            // inserted via char(2)/char(3) so no extra binders are needed; they don't occur in real
            // feed text and are stripped in the UI. `search_text` (column 1) is still MATCHed so
            // body-only hits are returned, but it isn't projected.
            sql = """
                SELECT a.id,
                       highlight(articles_fts, 0, char(2), char(3))
                FROM articles a
                INNER JOIN articles_fts ON articles_fts.rowid = a.rowid
                WHERE articles_fts MATCH ?
                  AND a.deleted_at IS NULL
                ORDER BY articles_fts.rank;
            """.trimIndent(),
            mapper = { cursor ->
                while (cursor.next().value) {
                    val id = cursor.getString(0) ?: continue
                    hits.add(
                        FtsHit(
                            id = id,
                            titleMarked = cursor.getString(1).orEmpty(),
                        ),
                    )
                }
                QueryResult.Unit
            },
            parameters = 1,
            binders = { bindString(0, matchArg) },
        )
        return hits
    }

    companion object {
        /** Sentinel wrapping the start of a matched span in [FtsHit] markup (ASCII STX, `char(2)`). */
        const val MARK_START: Char = '\u0002'

        /** Sentinel wrapping the end of a matched span in [FtsHit] markup (ASCII ETX, `char(3)`). */
        const val MARK_END: Char = '\u0003'
    }
}
