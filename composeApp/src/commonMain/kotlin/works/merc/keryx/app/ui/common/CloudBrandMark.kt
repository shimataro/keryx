package works.merc.keryx.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.dropbox
import works.merc.keryx.app.resources.google_drive

/**
 * The mark shown beside a cloud provider's name (Setup cards, Settings ▸ Cloud sync rows) — the one
 * place that decides it, so the two screens cannot drift.
 *
 * Dropbox and Google Drive show the provider's own, unmodified logo (`drawable/dropbox.png`,
 * `drawable/google_drive.png`; sources and usage terms in `docs/app-architecture.md`), drawn untinted
 * because both providers forbid recoloring. OneDrive shows a generic cloud instead: Microsoft's
 * trademark guidelines allow naming OneDrive in text but require a license for its logo or icon, so
 * the name beside the mark is the only OneDrive branding. Being Keryx's own glyph, the cloud follows
 * the surrounding content color like any other icon.
 *
 * The PNGs are 128 px, which covers the largest slot (28 dp at 3x); [FilterQuality.Medium] keeps their
 * downscale on a 1x–2x display smooth instead of shimmering at the edges.
 */
@Composable
fun CloudBrandMark(type: CloudStorageType, modifier: Modifier = Modifier) {
    when (type) {
        CloudStorageType.DROPBOX -> BrandLogo(Res.drawable.dropbox, modifier)
        CloudStorageType.GOOGLE_DRIVE -> BrandLogo(Res.drawable.google_drive, modifier)
        CloudStorageType.ONEDRIVE -> KeryxIcon(KeryxIcons.Cloud, contentDescription = null, modifier = modifier)
    }
}

@Composable
private fun BrandLogo(logo: DrawableResource, modifier: Modifier) {
    Image(
        bitmap = imageResource(logo),
        contentDescription = null,
        modifier = modifier,
        filterQuality = FilterQuality.Medium,
    )
}
