package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds

/**
 * Whether [a] and [b] are the same feed list in every field a view reads: the same size, in the
 * same order (the order the sidebar is built from), each pair equal once the one field a refresh
 * rewrites without anything on screen reading it — `updated_at` — is set aside.
 *
 * A refresh can re-emit the feed list once per fetched feed, often changing only that field, so a
 * UI that rebuilds its sidebar, selection target, id lookup and article rows' feed info from the
 * list can gate the rebuild on this instead of redoing all of it per fetched feed
 * ([HomeViewModel.structuralFeeds]). Whatever is built that way keeps holding the older `Feeds`
 * objects while this says "equal", so a field may be left out here only if no view reads it. (The
 * conditional-request validators and the error state are not on the feed row at all: they live in
 * the device-local `feed_fetch_state`, see [HomeViewModel.feedFetchProblems].)
 */
fun feedsStructurallyEqual(a: List<Feeds>, b: List<Feeds>): Boolean =
    a.size == b.size && a.indices.all { a[it].structural() == b[it].structural() }

private fun Feeds.structural(): Feeds = copy(updated_at = 0L)
