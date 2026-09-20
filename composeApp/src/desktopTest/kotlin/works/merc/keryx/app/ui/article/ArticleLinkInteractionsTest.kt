package works.merc.keryx.app.ui.article

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class ArticleLinkInteractionsTest {

    @Test
    fun `hovering over link shows tooltip with url`() = runDesktopComposeUiTest {
        setContent {
            LinkText(
                text = buildAnnotatedString {
                    append("Visit ")
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "https://example.com",
                            linkInteractionListener = {},
                        )
                    ) {
                        append("example")
                    }
                },
                style = TextStyle.Default,
                modifier = Modifier.testTag("link-text"),
            )
        }

        val bounds = onNodeWithTag("link-text").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("link-text").performMouseInput {
            moveTo(Offset(bounds.width / 2f, bounds.height / 2f))
        }
        waitForIdle()

        onNodeWithText("https://example.com").assertExists()
    }

    @Test
    fun `resolveLinkUrlAtOffset returns url for link and null for plain text`() = runDesktopComposeUiTest {
        val annotated = buildAnnotatedString {
            append("Visit ")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "https://example.com",
                    linkInteractionListener = {},
                )
            ) {
                append("example")
            }
            append(" for more")
        }
        var layoutResult: TextLayoutResult? = null
        setContent {
            Text(
                text = annotated,
                style = TextStyle.Default,
                modifier = Modifier.testTag("test-text"),
                onTextLayout = { layoutResult = it },
            )
        }
        waitForIdle()
        val result = layoutResult ?: error("TextLayoutResult was not captured")

        val nodeBounds = onNodeWithTag("test-text").fetchSemanticsNode().boundsInRoot
        val centerY = nodeBounds.height / 2f

        // Position inside the link text ("example") — expecting the URL
        val linkX = nodeBounds.width * 0.30f
        assertEquals(
            "https://example.com",
            resolveLinkUrlAtOffset(annotated, result, Offset(linkX, centerY)),
        )

        // Position inside plain text ("Visit ") — expecting null
        val plainX = nodeBounds.width * 0.10f
        assertNull(
            resolveLinkUrlAtOffset(annotated, result, Offset(plainX, centerY)),
        )

        // Position outside text bounds — expecting null
        assertNull(
            resolveLinkUrlAtOffset(annotated, result, Offset(nodeBounds.width + 50f, centerY)),
        )
    }
}
