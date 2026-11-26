package works.merc.keryx.app.data.local

import works.merc.keryx.app.data.local.db.Articles

/**
 * Whether an article is shown: not deleted, **or** starred.
 *
 * An article's star and its deletion are two independent last-write-wins groups in the sync merge
 * (`SyncLww.ARTICLE_STAR`, `SyncLww.ARTICLE_DELETION`), so a row can hold both — a star made on one
 * device and a cache cleanup made on another, merged in either order. A star keeps the article, so a
 * starred row stays visible whatever its deletion says. The rule is applied when an article is read,
 * never by the merge (a merge-time rule across the two groups would break its commutativity).
 *
 * Every query that decides whether an article is listed, counted, searched or acted on uses this
 * predicate: the `articles.sq` queries spell it out (`(deleted_at IS NULL OR is_starred = 1)`, guarded by
 * `ArticleVisibilityQueriesTest`), raw SQL uses [articleVisibleSql], and Kotlin uses [isVisible].
 */
fun articleVisibleSql(alias: String): String {
    val prefix = if (alias.isEmpty()) "" else "$alias."
    return "(${prefix}deleted_at IS NULL OR ${prefix}is_starred = 1)"
}

/** [articleVisibleSql] for a row already read into memory. */
val Articles.isVisible: Boolean get() = deleted_at == null || is_starred == 1L
