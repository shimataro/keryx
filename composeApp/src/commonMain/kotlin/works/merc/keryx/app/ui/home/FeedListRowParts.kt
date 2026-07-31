package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.painterResource
import works.merc.keryx.app.ui.common.KeryxIcons

// Small leaf composables used to build the rows of FeedListPane (feed avatar + unread-count badge).

/**
 * Displays a feed avatar using its favicon when available, or the feed title's initial as a fallback.
 *
 * @param title The feed title used to create the fallback avatar.
 * @param faviconUrl The favicon URL, or `null` or blank when the title-based avatar should be displayed.
 */
@Composable
internal fun FeedAvatar(title: String, faviconUrl: String?) {
    if (faviconUrl.isNullOrBlank()) {
        LetterAvatar(title)
    } else {
        // The favicon must NOT sit in its own graphics layer: the feed row's
        // `Modifier.dragAndDropSource` records the row into a Picture and draws that snapshot,
        // and a nested layer's async update (the favicon finishing loading) doesn't invalidate
        // that recorder — so the favicon would stay invisible until a hover-driven redraw.
        // Hence rounded corners are clipped at the canvas level (drawWithCache + clipPath, which
        // creates no layer) instead of `Modifier.clip`, and Coil's own `clipToBounds` is disabled.
        Box(
            Modifier.size(18.dp).drawWithCache {
                val r = 4.dp.toPx()
                val path = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r, r)) }
                onDrawWithContent { clipPath(path) { this@onDrawWithContent.drawContent() } }
            },
        ) {
            AsyncImage(
                model = faviconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(KeryxIcons.PublicFilled),
                clipToBounds = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Displays a circular avatar containing the first character of the title.
 *
 * @param title The title from which to derive the avatar character.
 */
@Composable
private fun LetterAvatar(title: String) {
    val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(18.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * Displays a count badge using a color appropriate for the current selection, focus, drag-source,
 * and drop-target state.
 *
 * @param count The count to display.
 * @param selected Whether the badge is selected.
 * @param focused Whether the badge is focused.
 * @param isDropTarget Whether the row is an active drag-and-drop target.
 * @param isDragSource Whether the row is the source of the currently dragged feed.
 */
@Composable
internal fun CountBadge(
    count: Long,
    selected: Boolean,
    focused: Boolean,
    isDropTarget: Boolean = false,
    isDragSource: Boolean = false,
) {
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = dropTargetContentColorOrNull(isDropTarget, selected, focused, isDragSource)
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
