package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [hasEnoughFreeSpaceForUpdate] pulled out to a pure function specifically so this overflow
 * behavior — and the ordinary boundary — can be verified without touching a real filesystem or an
 * [UpdateRepository] instance. See that function's own KDoc for why it compares via division rather
 * than a plain `usableBytes < assetSizeBytes * 3` — the multiplication form silently overflows a
 * `Long` for a large enough [assetSizeBytes] and would then wrongly report "enough space".
 */
class UpdateFreeSpaceGuardTest {

    @Test
    fun exactlyTheRequiredMultipleIsEnough() {
        // REQUIRED_FREE_SPACE_MULTIPLE is 3 — usable space exactly 3x the asset is the boundary.
        assertTrue(hasEnoughFreeSpaceForUpdate(usableBytes = 300, assetSizeBytes = 100))
    }

    @Test
    fun oneByteShortOfTheRequiredMultipleIsNotEnough() {
        assertFalse(hasEnoughFreeSpaceForUpdate(usableBytes = 299, assetSizeBytes = 100))
    }

    @Test
    fun anAssetSizeThatWouldOverflowMultiplicationIsCorrectlyRejected() {
        // Long.MAX_VALUE / 2 * 3 overflows into a negative Long under plain multiplication, which
        // would make `usableBytes < requiredBytes` false for any usableBytes — i.e. "enough space"
        // — regardless of how little is actually free. Division-based comparison must not fall for
        // this: no real amount of usable space can ever be enough for an asset this large.
        val hugeAssetSize = Long.MAX_VALUE / 2
        assertFalse(hasEnoughFreeSpaceForUpdate(usableBytes = Long.MAX_VALUE, assetSizeBytes = hugeAssetSize))
    }

    @Test
    fun aNegativeAssetSizeIsNeverEnough() {
        // Should never reach here in practice (selectUpdateAsset already rejects it), but this
        // function stays defensive on its own rather than relying solely on that earlier gate.
        assertFalse(hasEnoughFreeSpaceForUpdate(usableBytes = Long.MAX_VALUE, assetSizeBytes = -1))
    }

    @Test
    fun plentyOfSpaceIsEnough() {
        assertTrue(hasEnoughFreeSpaceForUpdate(usableBytes = 1_000_000_000, assetSizeBytes = 1_000))
    }

    @Test
    fun noSpaceAtAllIsNotEnoughForANonZeroAsset() {
        assertFalse(hasEnoughFreeSpaceForUpdate(usableBytes = 0, assetSizeBytes = 1))
    }
}
