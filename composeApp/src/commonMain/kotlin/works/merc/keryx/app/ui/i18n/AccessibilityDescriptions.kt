package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.accessibility_checked
import works.merc.keryx.app.resources.accessibility_unchecked

/**
 * Localized state description for a checked UI item (used by accessibility services).
 */
@Composable
fun checkedStateDescription(): String = stringResource(Res.string.accessibility_checked)

/**
 * Localized state description for an unchecked UI item (used by accessibility services).
 */
@Composable
fun uncheckedStateDescription(): String = stringResource(Res.string.accessibility_unchecked)
