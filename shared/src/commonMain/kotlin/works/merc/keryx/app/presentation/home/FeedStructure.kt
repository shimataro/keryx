package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds

/**
 * Whether [a] and [b] are the same feed list in every field a view reads: the same size, in the
 * same order (the order the sidebar is built from), each pair equal once the three fields a
 * refresh rewrites without anything on screen reading them — `etag`, `last_modified` and
 * `updated_at` — are set aside.
 *
 * A refresh re-emits the feed list once per fetched feed, mostly changing only those three, so a UI
 * that rebuilds its sidebar, selection target, id lookup and article rows' feed info from the list
 * can gate the rebuild on this instead of redoing all of it per fetched feed
 * ([HomeViewModel.structuralFeeds]). Whatever is built that way keeps holding the older `Feeds`
 * objects while this says "equal", so a field may be left out here only if no view reads it;
 * anything that needs the fresh conditional-request fields (a refresh sends `etag` /
 * `last_modified`) must resolve the current row from [HomeViewModel.feeds] instead.
 */
fun feedsStructurallyEqual(a: List<Feeds>, b: List<Feeds>): Boolean =
    a.size == b.size && a.indices.all { a[it].structural() == b[it].structural() }

private fun Feeds.structural(): Feeds = copy(etag = null, last_modified = null, updated_at = 0L)
