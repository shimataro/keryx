package works.merc.keryx.app.ui.article

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.ClipboardEntries
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.menu_copy_link_address
import works.merc.keryx.app.resources.menu_open_link
import works.merc.keryx.app.ui.home.isHttpOrHttpsUrl

/**
 * A [Text] wrapper that adds a hover tooltip showing link URLs and a context menu
 * for inline links inside the fallback article reader.
 *
 * The clickable behaviour itself is still carried by [LinkAnnotation.Clickable] inside the
 * [AnnotatedString]; this composable only adds hover and right-click affordances on top.
 */
@Composable
internal fun LinkText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Keyed on the text: a block-level slot here is reused across articles (the reader's LazyColumn
    // items are not keyed), so a pointer resting over a link while the content changes underneath it
    // would otherwise keep showing the previous article's URL until the next pointer event.
    var hoveredLinkUrl by remember(text) { mutableStateOf<String?>(null) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val linkAtHit = remember { mutableStateOf<String?>(null) }

    val menuOpenLink = stringResource(Res.string.menu_open_link)
    val menuCopyLinkAddress = stringResource(Res.string.menu_copy_link_address)

    val currentText by rememberUpdatedState(text)
    val currentLayoutResult by rememberUpdatedState(textLayoutResult)

    val hitTest: (Offset) -> Boolean = { offset ->
        val url = resolveLinkUrlAtOffset(currentText, currentLayoutResult, offset)
        linkAtHit.value = url
        url != null
    }
    val currentHitTest by rememberUpdatedState(hitTest)

    val contextMenuModifier = Modifier.nativeContextMenu(
        items = {
            val url = linkAtHit.value
            if (url != null) {
                listOf(
                    NativeMenuItem(
                        label = menuOpenLink,
                        onClick = { if (isHttpOrHttpsUrl(url)) BrowserOpener.open(url) },
                    ),
                    NativeMenuItem(
                        label = menuCopyLinkAddress,
                        onClick = { scope.launch { clipboard.setClipEntry(ClipboardEntries.ofText(url)) } },
                    ),
                )
            } else {
                emptyList()
            }
        },
        hitTest = { offset -> currentHitTest(offset) },
    )

    Layout(
        modifier = modifier
            .then(contextMenuModifier)
            .pointerInput(text) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter,
                            PointerEventType.Move,
                            PointerEventType.Press,
                            PointerEventType.Release,
                            -> {
                                val pos = event.changes.first().position
                                pointerPosition = pos
                                // Compose freezes hit testing while a pointer is pressed, so this
                                // node keeps receiving Move events after the pointer has already
                                // left it. Show nothing until the drag is over.
                                hoveredLinkUrl = if (event.changes.any { it.pressed }) {
                                    null
                                } else {
                                    resolveLinkUrlAtOffset(text, textLayoutResult, pos)
                                }
                            }

                            PointerEventType.Exit -> {
                                hoveredLinkUrl = null
                            }
                        }
                    }
                }
            },
        content = {
            Text(
                text = text,
                style = style,
                color = color,
                textAlign = textAlign,
                softWrap = softWrap,
                onTextLayout = { textLayoutResult = it },
            )
            val tooltipUrl = hoveredLinkUrl
            if (tooltipUrl != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = tooltipUrl,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = TextStyle(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints)
        val tooltipMeasurable = measurables.getOrNull(1)
        val tooltipPlaceable = tooltipMeasurable?.measure(androidx.compose.ui.unit.Constraints())
        layout(textPlaceable.width, textPlaceable.height) {
            textPlaceable.placeRelative(0, 0)
            tooltipPlaceable?.placeRelative(
                pointerPosition.x.toInt(),
                pointerPosition.y.toInt() + 20.dp.roundToPx(),
            )
        }
    }
}

/** Resolves the URL of a [LinkAnnotation.Clickable] at [offset] inside [text], if any. */
internal fun resolveLinkUrlAtOffset(
    text: AnnotatedString,
    layoutResult: TextLayoutResult?,
    offset: Offset,
): String? {
    val result = layoutResult ?: return null
    // getOffsetForPosition clamps an out-of-range position onto the nearest character, so a
    // position outside the laid-out text would otherwise resolve to the link at its edge.
    if (!result.containsPosition(offset)) return null
    val textOffset = result.getOffsetForPosition(offset)
    if (textOffset < 0 || textOffset >= text.length) return null
    val annotations = text.getLinkAnnotations(textOffset, textOffset + 1)
    return (annotations.firstOrNull()?.item as? LinkAnnotation.Clickable)?.tag
}

/** Whether [position] really falls inside the rectangle of the line it resolves to. */
private fun TextLayoutResult.containsPosition(position: Offset): Boolean {
    // getLineForVerticalPosition clamps too, so the resolved line's own bounds are re-checked.
    val line = getLineForVerticalPosition(position.y)
    if (position.y < getLineTop(line) || position.y > getLineBottom(line)) return false
    return position.x >= getLineLeft(line) && position.x <= getLineRight(line)
}
