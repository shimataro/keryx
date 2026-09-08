package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Desktop `actual`: never rendered in production — desktop always resolves `PaneLayout.Triple`
 * (see `Constants.kt`'s `TRIPLE_PANE_MIN_WIDTH`/`WINDOW_MIN_WIDTH` KDoc), where the feed list is a
 * permanent pane with its own editable `KeryxTextField` instead of this bar — a back arrow beside
 * a plain [KeryxTextField], matching this app's flat native-feel look. Implemented anyway so
 * `desktopTest` can render and assert this composable directly.
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
