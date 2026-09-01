package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import works.merc.keryx.app.core.SEARCH_FALLBACK_RESULT_LIMIT
import works.merc.keryx.app.core.TRIGRAM_MIN_TERM_LENGTH
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
 * Runs full-text search and returns matching active articles with matched title terms marked for
 * highlighting.
 *
 * Terms at or above [TRIGRAM_MIN_TERM_LENGTH] are matched via the `articles_fts` trigram index
 * (rank-ordered); shorter terms (`searchTerms` already dropped anything below
 * [works.merc.keryx.app.core.SEARCH_MIN_TERM_LENGTH]) can't be matched by trigrams at all — the
 * tokenizer produces no tokens under 3 characters — so they're applied as an additional `LIKE`
 * filter instead. A query made up entirely of short terms has no FTS query to rank by, so it
 * falls back to a plain `LIKE` scan over `articles`, ordered by recency and capped at
 * [SEARCH_FALLBACK_RESULT_LIMIT].
 *
 * Highlighting is done in Kotlin (not via FTS5's `highlight()`) so that short (LIKE-only) terms get
 * marked the same way as trigram-matched ones — `highlight()` only knows about the FTS5 query, so
 * it can't see terms that were applied as a bolt-on `LIKE` filter.
 */
class FtsSearch(private val driver: SqlDriver) {

    /**
     * Searches articles using all terms in the query and returns matching active articles, with
     * matched title terms marked for highlighting.
     *
     * @param rawQuery The user-entered search query.
     * @return The matching article identifiers and marked titles.
     */
    fun search(rawQuery: String): List<FtsHit> {
        val terms = searchTerms(rawQuery)
        if (terms.isEmpty()) return emptyList()
        val (longTerms, shortTerms) = terms.partition { it.length >= TRIGRAM_MIN_TERM_LENGTH }
        return if (longTerms.isEmpty()) {
            searchByLikeOnly(shortTerms)
        } else {
            searchByFts(longTerms, shortTerms)
        }
    }

    /**
     * FTS path: at least one term is long enough for the trigram index. Any short terms are ANDed
     * in as an extra `LIKE` filter over the FTS-matched candidates (cheap, since MATCH already
     * narrowed the row set). Order is always FTS5 relevance rank, matching the FTS-only case exactly
     * when there are no short terms.
     */
    private fun searchByFts(longTerms: List<String>, shortTerms: List<String>): List<FtsHit> {
        // Each long term is wrapped as its own quoted FTS5 string so arbitrary user input (quotes,
        // operators like AND/OR/-/*, punctuation) is treated as literal text and can't produce a
        // MATCH syntax error. Embedded quotes are escaped. Order-independent (not a phrase search).
        val matchArg = longTerms.joinToString(" AND ") { "\"" + it.replace("\"", "\"\"") + "\"" }
        val likeClause = likeAndClause(shortTerms)
        val sql = buildString {
            append(
                """
                SELECT a.id, a.title
                FROM articles a
                INNER JOIN articles_fts ON articles_fts.rowid = a.rowid
                WHERE articles_fts MATCH ?
                  AND a.deleted_at IS NULL
                """.trimIndent(),
            )
            if (likeClause != null) append("\n  AND ").append(likeClause)
            append("\nORDER BY articles_fts.rank;")
        }
        val allTerms = longTerms + shortTerms
        val hits = mutableListOf<FtsHit>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val id = cursor.getString(0) ?: continue
                    val title = cursor.getString(1).orEmpty()
                    hits.add(FtsHit(id = id, titleMarked = markTerms(title, allTerms)))
                }
                QueryResult.Unit
            },
            parameters = 1 + shortTerms.size * 2,
            binders = {
                bindString(0, matchArg)
                bindLikeTerms(shortTerms, startIndex = 1)
            },
        )
        return hits
    }

    /**
     * Fallback path: every term is too short for the trigram index. Scans `articles` directly with
     * `LIKE`, ordered by recency (no FTS rank is available) and capped at
     * [SEARCH_FALLBACK_RESULT_LIMIT] so a common short term can't stream unbounded results into the
     * UI. Mirrors [searchByFts]'s `a.deleted_at IS NULL` filter exactly (no `feeds` join either,
     * matching the FTS path's existing scope).
     */
    private fun searchByLikeOnly(shortTerms: List<String>): List<FtsHit> {
        val likeClause = requireNotNull(likeAndClause(shortTerms)) { "searchByLikeOnly requires at least one term" }
        val sql = """
            SELECT a.id, a.title
            FROM articles a
            WHERE a.deleted_at IS NULL
              AND $likeClause
            ORDER BY a.published_at DESC, a.created_at DESC, a.id DESC
            LIMIT $SEARCH_FALLBACK_RESULT_LIMIT;
        """.trimIndent()
        val hits = mutableListOf<FtsHit>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    val id = cursor.getString(0) ?: continue
                    val title = cursor.getString(1).orEmpty()
                    hits.add(FtsHit(id = id, titleMarked = markTerms(title, shortTerms)))
                }
                QueryResult.Unit
            },
            parameters = shortTerms.size * 2,
            binders = { bindLikeTerms(shortTerms, startIndex = 0) },
        )
        return hits
    }

    /** `(a.title LIKE ? ESCAPE '\\' OR a.search_text LIKE ? ESCAPE '\\') AND ...`, one clause per term, ANDed. Null when [terms] is empty. */
    private fun likeAndClause(terms: List<String>): String? {
        if (terms.isEmpty()) return null
        return terms.joinToString(" AND ") { "(a.title LIKE ? ESCAPE '\\' OR a.search_text LIKE ? ESCAPE '\\')" }
    }

    /** Binds two `%pattern%` parameters (title, search_text) per term, starting at [startIndex]. */
    private fun SqlPreparedStatement.bindLikeTerms(terms: List<String>, startIndex: Int) {
        var index = startIndex
        for (term in terms) {
            val pattern = "%" + escapeLikePattern(term) + "%"
            bindString(index++, pattern)
            bindString(index++, pattern)
        }
    }

    companion object {
        /** Sentinel wrapping the start of a matched span in [FtsHit] markup (ASCII STX, `char(2)`). */
        const val MARK_START: Char = '\u0002'

        /** Sentinel wrapping the end of a matched span in [FtsHit] markup (ASCII ETX, `char(3)`). */
        const val MARK_END: Char = '\u0003'
    }
}

/** Escapes `\`, `%`, and `_` with a leading `\` so [term] is safe inside a `LIKE ... ESCAPE '\\'` pattern. */
internal fun escapeLikePattern(term: String): String =
    term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * Wraps every case-insensitive occurrence of any of [terms] in [text] with [FtsSearch.MARK_START]/
 * [FtsSearch.MARK_END]. Overlapping matches (e.g. a long term and a short term that occurs inside
 * it) are merged into their union before markers are inserted — the consumer
 * ([works.merc.keryx.app.ui.home.markedToAnnotatedString]) is a flat toggle, not a stack, so emitting
 * raw overlapping START/END pairs would close the highlight early partway through a longer match.
 * Adjacent (non-overlapping) matches are left as separate marked spans, same as FTS5's own
 * `highlight()` did.
 */
internal fun markTerms(text: String, terms: List<String>): String {
    val ranges = mutableListOf<IntRange>()
    for (term in terms) {
        if (term.isEmpty()) continue
        var from = 0
        while (from <= text.length) {
            val index = text.indexOf(term, from, ignoreCase = true)
            if (index < 0) break
            ranges.add(index until (index + term.length))
            from = index + 1
        }
    }
    if (ranges.isEmpty()) return text

    ranges.sortBy { it.first }
    val merged = mutableListOf<IntRange>()
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last) {
            if (range.last > last.last) merged[merged.lastIndex] = last.first..range.last
        } else {
            merged.add(range)
        }
    }

    return buildString {
        var cursor = 0
        for (range in merged) {
            append(text, cursor, range.first)
            append(FtsSearch.MARK_START)
            append(text, range.first, range.last + 1)
            append(FtsSearch.MARK_END)
            cursor = range.last + 1
        }
        append(text, cursor, text.length)
    }
}
