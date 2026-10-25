package works.merc.keryx.app.ui.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FaviconShape]'s corner radius is a fraction of the favicon's size (see `ListRowChrome.kt`), not a
 * fixed pixel value, so every favicon has the same roundness whatever size it is drawn at.
 */
class FaviconShapeTest {

    @Test
    fun cornerIsOneEighthOfA32pxFavicon() {
        assertEquals(4f, FaviconShape.topStart.toPx(Size(32f, 32f), Density(1f)))
    }

    @Test
    fun cornerScalesWithAnIconSizedFavicon() {
        assertEquals(2.25f, FaviconShape.topStart.toPx(Size(18f, 18f), Density(1f)))
    }

    @Test
    fun cornerDependsOnlyOnTheShapeSizeNotTheDensity() {
        assertEquals(
            FaviconShape.topStart.toPx(Size(36f, 36f), Density(1f)),
            FaviconShape.topStart.toPx(Size(36f, 36f), Density(2f)),
        )
    }
}
