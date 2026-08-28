package works.merc.keryx.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A tappable settings row — the replacement for a bare hover-styled `Text` at a `commonMain` call
 * site (see `ui/settings/SettingsComponents.kt`'s `LinkRow`/`ActionLinkRow`/`SwitchRow`, which are
 * now thin wrappers around this). Desktop's `actual` keeps the app's existing flat convention
 * (primary-colored text, underline + hand cursor on hover, a [trailing] slot rendered plainly
 * alongside a *non*-colored label when present); Android's `actual` uses a real M3 `ListItem`,
 * since hover has no touch equivalent and a `ListItem`'s own full-row tap target is what "native"
 * means there.
 *
 * @param supporting Shown as a hover tooltip on desktop (e.g. the destination URL for a link row)
 *   and as `ListItem`'s `supportingContent` on Android.
 * @param onClick Invoked when the row (or, on desktop with no [trailing], the label text) is
 *   tapped/clicked. `null` renders a non-interactive row.
 * @param trailing An optional trailing slot (e.g. `FlatSwitch`). When present, desktop renders the
 *   label in the default (non-primary, non-underlined) text color — matching the former
 *   `SwitchRow`'s look, where only the switch itself was ever the visibly-interactive element.
 * @param toggled When non-null, this row is a toggle (e.g. `SwitchRow`): Android exposes the whole
 *   row to accessibility services as one `Role.Switch` node carrying this checked state (rather
 *   than the row and [trailing]'s own switch merging into two separately-focusable nodes with no
 *   checked state announced at all), and [onClick] is expected to flip it. Desktop ignores this —
 *   its `trailing != null` branch already leaves the row itself non-interactive (see above), so
 *   there is nothing to attach a checked state to there.
 */
@Composable
expect fun KeryxSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    toggled: Boolean? = null,
)
