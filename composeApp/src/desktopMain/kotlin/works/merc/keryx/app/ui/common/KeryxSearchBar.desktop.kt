package works.merc.keryx.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Desktop `actual`: never rendered in production — desktop always resolves `PaneLayout.Triple`
 * (see `Constants.kt`'s `TRIPLE_PANE_MIN_WIDTH`/`WINDOW_MIN_WIDTH` KDoc), where `FeedListPane`
 * keeps its original editable `KeryxTextField` instead of this bar. Implemented anyway so
 * `desktopTest` can render and assert this composable directly, matching this app's flat,
 * hairline-bordered native-feel look (see the `ui-guidelines` skill) rather than M3's own tokens.
 */
@Composable
actual fun KeryxCollapsedSearchBar(
    text: String,
    isPlaceholder: Boolean,
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = KeryxTextFieldDefaults.MinHeight)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = KeryxTextFieldDefaults.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeryxIcon(KeryxIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.padding(start = 8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaceholder) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Desktop `actual`: never rendered in production (see [KeryxCollapsedSearchBar]'s own KDoc for
 * why) — a back arrow beside a plain [KeryxTextField], matching this app's flat native-feel look.
 * Implemented for `desktopTest` coverage of the narrow-layout search screen.
 */
@Composable
actual fun KeryxExpandedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onNavigateUp: () -> Unit,
    navigateUpEnabled: Boolean,
    navigateUpContentDescription: String,
    clearContentDescription: String,
    onSearchAction: () -> Unit,
    modifier: Modifier,
    fieldModifier: Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(
            tooltip = navigateUpContentDescription,
            onClick = onNavigateUp,
            enabled = navigateUpEnabled,
        ) {
            KeryxIcon(KeryxIcons.ArrowBack, contentDescription = navigateUpContentDescription)
        }
        KeryxTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = placeholder,
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    TooltipIconButton(
                        tooltip = clearContentDescription,
                        size = 32.dp,
                        onClick = { onQueryChange("") },
                    ) {
                        KeryxIcon(KeryxIcons.CloseFilled, contentDescription = clearContentDescription)
                    }
                }
            },
            modifier = Modifier.weight(1f).then(fieldModifier),
        )
    }
}
