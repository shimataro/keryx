package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
actual fun FlatButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
actual fun FlatTonalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val background = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
actual fun FlatTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}
