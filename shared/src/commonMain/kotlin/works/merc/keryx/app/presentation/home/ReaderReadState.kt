package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Articles

/**
 * Whether the reader's read/unread button should show the unread state.
 *
 * Once the cursor's article body has loaded, that is the reader's own [article]: unread, and the article the
 * selection [cursor] points at. The reader's article lags the cursor while a newly selected article's body
 * loads, so for that moment it is still the previous article. Drawing the previous article's state there would
 * flip the button as soon as the new article (read by its selection) arrives, which iOS animates. Until then
 * the button shows the state the incoming article will almost always have — read — unless an explicit
 * read/unread has already been made on it ([pendingRead]), which it shows at once so a second tap undoes the first.
 *
 * @param article The article the reader currently holds, or `null` when nothing is selected.
 * @param cursor The id the selection cursor points at, or `null` when nothing is selected.
 * @param pendingRead The read state asked for on the cursor's article while it was loading (see
 *   [pendingReadIntent]), or `null` when none was; ignored once [article] is the cursor's article.
 */
fun isShownUnread(article: Articles?, cursor: String?, pendingRead: Boolean? = null): Boolean = when {
    cursor == null -> false
    article?.id == cursor -> article.is_read == 0L
    else -> pendingRead == false
}
