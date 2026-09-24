package works.merc.keryx.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.xml.sax.InputSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against a vector asset that parses fine but draws nothing — e.g. path data copied from a
 * Material Symbols *web* SVG (`viewBox="0 -960 960 960"`, every y negative) into an Android vector
 * whose viewport is `0..960`, which puts the whole glyph outside the viewport. Compilation and
 * every behavioral test pass regardless; only the rendered pixels reveal it.
 *
 * Reads the drawables straight from the source tree (Gradle runs tests with the module directory
 * as the working directory) so the Android-only set, which desktop's resource bundle never
 * includes, is covered too.
 */
@OptIn(ExperimentalTestApi::class)
class KeryxIconAssetsTest {

    private val drawableDirs = listOf(
        "src/commonMain/composeResources/drawable",
        "src/androidMain/composeResources/drawable",
    )

    @Test
    fun everyVectorDrawableRendersVisiblePixels() {
        val files = drawableDirs.flatMap { dir ->
            File(dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList()
        }.sortedBy { it.path }
        assertTrue(files.isNotEmpty(), "no drawables found — working directory is ${File(".").absolutePath}")

        val blank = files.filterNot { rendersVisiblePixels(it) }
        assertTrue(blank.isEmpty(), "vector drawables that render no visible pixels: ${blank.map { it.path }}")
    }

    private fun rendersVisiblePixels(file: File): Boolean {
        var visible = false
        runDesktopComposeUiTest {
            val vector = file.inputStream().use { loadXmlImageVector(InputSource(it), Density(1f)) }
            setContent {
                Image(rememberVectorPainter(vector), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            waitForIdle()
            val pixels = onRoot().captureToImage().toPixelMap()
            visible = (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y].alpha > 0f } }
        }
        return visible
    }
}
