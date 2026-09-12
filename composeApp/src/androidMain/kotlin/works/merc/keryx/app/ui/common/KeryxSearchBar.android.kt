package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

/**
 * Android `actual`: M3's own [SearchBarDefaults.InputField] (the [TextFieldState]-based overload),
 * held permanently expanded — see the `expect`'s KDoc in `commonMain`. This is the real M3 search
 * field, so it inherits the same pill shape, container color, and `minHeight` (56dp, which grows
 * rather than clips under the app's font-size setting) as M3's own collapsed search bar.
 *
 * The [TextFieldState]-based overload is deliberate, not the simpler `query: String`/
 * `onQueryChange` one: that overload's internal `BasicTextField(value = query, ...)` resets the
 * caret to the *start* of the text on every remount whose initial `query` is already non-empty —
 * confirmed on-device (`KeryxSearchBarAndroidTest`) — which is exactly the case every time the
 * user reopens this pane with a query left over from an earlier visit (type a query, exit Search,
 * come back to it). [rememberQueryFieldState] owns a [TextFieldState] instead,
 * whose own constructor already defaults `initialSelection` to the end of `initialText` — the
 * same "external value places the caret at the end" contract [KeryxTextField] already guarantees
 * for the same reason.
 *
 * `SearchBarDefaults.InputField`'s own `onExpandedChange` is a no-op here: this bar is always
 * rendered already expanded (it *is* the search screen's header, not a collapsible overlay above
 * it), so there is no separate expanded/collapsed state to toggle.
 *
 * This is the pane's topmost element at a narrow layout (`SearchListPane`'s own header), and
 * `HomeScreen`'s `Scaffold` draws content edge-to-edge (`contentWindowInsets = WindowInsets(0)`),
 * so — same contract as [KeryxPaneTopBar]'s Android `actual` — this bar must reserve its own top
 * inset rather than rely on a `TopAppBar`'s default (avoided here per this file's own KDoc, above,
 * for the font-scale clipping reason) or an ancestor already having consumed it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxExpandedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onNavigateUp: () -> Unit,
    navigateUpContentDescription: String,
    clearContentDescription: String,
    onSearchAction: () -> Unit,
    modifier: Modifier,
    fieldModifier: Modifier,
) {
    val textFieldState = rememberQueryFieldState(query, onQueryChange)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TooltipIconButton(
            tooltip = navigateUpContentDescription,
            onClick = onNavigateUp,
        ) {
            KeryxIcon(KeryxIcons.ArrowBack, contentDescription = navigateUpContentDescription)
        }
        SearchBarDefaults.InputField(
            state = textFieldState,
            onSearch = { onSearchAction() },
            expanded = true,
            onExpandedChange = {},
            modifier = Modifier.weight(1f).then(fieldModifier),
            placeholder = { Text(placeholder) },
            trailingIcon = if (query.isEmpty()) {
                null
            } else {
                {
                    TooltipIconButton(
                        tooltip = clearContentDescription,
                        size = 32.dp,
                        onClick = { textFieldState.clearText() },
                    ) {
                        KeryxIcon(KeryxIcons.CloseFilled, contentDescription = clearContentDescription)
                    }
                }
            },
        )
    }
}

/**
 * Bridges M3's [TextFieldState]-based [SearchBarDefaults.InputField] to `HomeViewModel.searchQuery`
 * (a plain `StateFlow<String>`, which stays the single source of truth) — a one-way, UI-to-VM
 * bridge, not a round trip: `HomeViewModel.setSearchQuery`'s only production callers are this field
 * itself and its own clear action (confirmed by grep), so nothing external ever writes a divergent
 * value back into [query] while this composable is mounted, and the returned `TextFieldState` is
 * `remember`ed exactly once per mount — [query]'s value at that moment seeds [TextFieldState]'s
 * own initial text/selection (caret at the end, its constructor's own default) and every later
 * change to [query] is simply not read again.
 */
@Composable
private fun rememberQueryFieldState(query: String, onQueryChange: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(initialText = query) }
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(state) {
        // snapshotFlow always replays the current value once on subscription — without dropping
        // it, mounting with a non-empty query would immediately call onQueryChange with the exact
        // value it was just given. Harmless today (HomeViewModel.setSearchQuery no-ops when the
        // value is unchanged) but not something to depend on.
        snapshotFlow { state.text.toString() }.drop(1).collect { currentOnQueryChange(it) }
    }
    return state
}
