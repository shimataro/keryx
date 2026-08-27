package works.merc.keryx.app.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp

/**
 * Android `actual`: a real M3 [ModalBottomSheet] — see the `expect`'s KDoc in `commonMain`.
 * [alignment]/[anchorOffsetY] are desktop-only positioning and have no meaning for a bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun KeryxAnchoredPanel(
    onDismissRequest: () -> Unit,
    alignment: Alignment,
    anchorOffsetY: Dp,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(),
    ) {
        content()
    }
}
