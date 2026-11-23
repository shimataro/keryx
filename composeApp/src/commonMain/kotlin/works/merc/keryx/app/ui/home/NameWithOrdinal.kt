package works.merc.keryx.app.ui.home

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.name_with_ordinal

/**
 * Renders a tag/folder display name with the ordinal that tells it apart from same-named rows —
 * the one formatter every display of a tag/folder name in this UI goes through (sidebar rows,
 * delete confirmations, drag labels, context and application menus, the article list's title), so
 * a second "Work" always reads "Work (2)" the same way everywhere. The ordinal itself is
 * `:shared`'s decision (`NameOrdinals.kt`'s `tagOrdinals`/`folderOrdinals`); only the localized
 * pattern lives here. The rename fields' initial values and OPML export deliberately bypass it —
 * they need the row's own name.
 *
 * [ordinal] is `null` for a uniquely named row (and for the first of a name — the ordinals only
 * number the second and later), which shows the bare name.
 */
@Composable
internal fun nameWithOrdinal(name: String, ordinal: Int?): String =
    if (ordinal == null) name else stringResource(Res.string.name_with_ordinal, name, ordinal)
