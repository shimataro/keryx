package works.merc.keryx.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Covers [menuSignature], which keys the effect that relabels the already-built native menu
 * widgets. Two properties matter and pull against each other: menus that render the same must
 * compare equal (or the effect relaunches for every visible row on every frame while scrolling),
 * and menus that render differently must compare unequal (or the menu keeps stale labels).
 */
class NativeMenuSignatureTest {

    /**
     * Submenu labels are user-entered tag / folder names, so they can contain anything a
     * delimiter-joined encoding would use as a separator. Both lists below collapsed to the single
     * string `sub:Tags:item:a,item:b,item:c` while the signature was built by joining
     * `"item:$label"` with `,`.
     */
    @Test
    fun distinguishesSubMenuChildrenWhoseLabelsContainTheDelimiters() {
        val first = listOf<NativeMenuEntry>(
            NativeSubMenu("Tags", listOf(NativeMenuItem("a,item:b") {}, NativeMenuItem("c") {})),
        )
        val second = listOf<NativeMenuEntry>(
            NativeSubMenu("Tags", listOf(NativeMenuItem("a") {}, NativeMenuItem("b,item:c") {})),
        )

        assertNotEquals(menuSignature(first), menuSignature(second))
    }

    /**
     * The same collision in the form this app really builds — the "Tags" and "Move to folder"
     * submenus hold [NativeCheckMenuItem]s. Both lists below collapsed to the single string
     * `check:true:Tech,check:false:Design,check:false:X`, and both have two check children, so
     * `menuShape` matches too and the widgets are not rebuilt either.
     */
    @Test
    fun distinguishesCheckedChildrenWhoseLabelsContainTheDelimiters() {
        val first = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Tags",
                listOf(
                    NativeCheckMenuItem("Tech", checked = true) {},
                    NativeCheckMenuItem("Design,check:false:X", checked = false) {},
                ),
            ),
        )
        val second = listOf<NativeMenuEntry>(
            NativeSubMenu(
                "Tags",
                listOf(
                    NativeCheckMenuItem("Tech,check:false:Design", checked = true) {},
                    NativeCheckMenuItem("X", checked = false) {},
                ),
            ),
        )

        assertNotEquals(menuSignature(first), menuSignature(second))
    }

    /**
     * The reason the signature exists at all. The entries carry `onClick` lambdas that capture a
     * row's own data, so they are fresh, unequal instances on every recomposition — keying the
     * effect on them would rewrite every visible row's native menu on every frame.
     */
    @Test
    fun equalContentGivesEqualSignaturesDespiteFreshLambdas() {
        // Each call captures its own value, as the real call site captures the row's own data —
        // that is what makes the entries unequal even though they render identically.
        fun build(): List<NativeMenuEntry> {
            val captured = Any()
            return listOf(
                NativeMenuItem("Refresh") { captured.hashCode() },
                NativeSubMenu(
                    "Tags",
                    listOf(
                        NativeCheckMenuItem("Tech", checked = true) { captured.hashCode() },
                        NativeCheckMenuItem("Design", checked = false) { captured.hashCode() },
                    ),
                ),
            )
        }

        assertNotEquals(build(), build(), "entries themselves must not be usable as the key")
        assertEquals(menuSignature(build()), menuSignature(build()))
    }

    @Test
    fun aFlippedCheckStateChangesTheSignature() {
        val before = listOf<NativeMenuEntry>(
            NativeSubMenu("Tags", listOf(NativeCheckMenuItem("Tech", checked = false) {})),
        )
        val after = listOf<NativeMenuEntry>(
            NativeSubMenu("Tags", listOf(NativeCheckMenuItem("Tech", checked = true) {})),
        )

        assertNotEquals(menuSignature(before), menuSignature(after))
    }

    @Test
    fun aRenamedLabelChangesTheSignature() {
        val before = listOf<NativeMenuEntry>(NativeMenuItem("Refresh") {})
        val after = listOf<NativeMenuEntry>(NativeMenuItem("Refresh all") {})

        assertNotEquals(menuSignature(before), menuSignature(after))
    }

    /**
     * A plain item and a check item that happen to share a label are different widgets, so they
     * must not share a signature — `checked` being null is what separates them.
     */
    @Test
    fun aPlainItemAndACheckItemWithTheSameLabelDiffer() {
        val plain = listOf<NativeMenuEntry>(NativeMenuItem("Tech") {})
        val checked = listOf<NativeMenuEntry>(NativeCheckMenuItem("Tech", checked = false) {})

        assertNotEquals(menuSignature(plain), menuSignature(checked))
    }

    /**
     * A separator carries no state, so two menus built with separators in the same positions must
     * compare equal — otherwise every row with a separator would resync its native widgets on
     * every recomposition, the same class of bug [equalContentGivesEqualSignaturesDespiteFreshLambdas]
     * guards against for leaves.
     */
    @Test
    fun separatorsInTheSamePositionsGiveEqualSignatures() {
        val first = listOf(NativeMenuItem("Refresh") {}, NativeMenuSeparator, NativeMenuItem("Unsubscribe") {})
        val second = listOf(NativeMenuItem("Refresh") {}, NativeMenuSeparator, NativeMenuItem("Unsubscribe") {})

        assertEquals(menuSignature(first), menuSignature(second))
    }
}
