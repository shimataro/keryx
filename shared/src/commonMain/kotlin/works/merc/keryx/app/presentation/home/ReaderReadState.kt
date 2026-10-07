package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Articles

/**
 * Whether the reader's read/unread button should show the unread state: [article] is unread *and* is
 * the article the selection [cursor] points at.
 *
 * The reader's article lags the cursor while a newly selected article's body loads, so for that moment
 * it is still the previous article. Drawing the previous article's unread state there would flip the
 * button as soon as the new article (read by its selection) arrives, which iOS animates. Until then the
 * button shows the state the incoming article will almost always have — read.
 *
 * @param article The article the reader currently holds, or `null` when nothing is selected.
 * @param cursor The id the selection cursor points at, or `null` when nothing is selected.
 */
fun isShownUnread(article: Articles?, cursor: String?): Boolean =
    article != null && article.is_read == 0L && article.id == cursor
