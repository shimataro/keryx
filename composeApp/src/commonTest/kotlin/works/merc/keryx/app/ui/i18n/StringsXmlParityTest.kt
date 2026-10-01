package works.merc.keryx.app.ui.i18n

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the two shipped locales (values/strings.xml = English default, values-ja/strings.xml =
 * Japanese) against silently drifting apart: a key present in one but not the other would fall
 * back to the wrong language for that one string instead of failing loudly.
 */
class StringsXmlParityTest {

    private val resourcesDir = File("src/commonMain/composeResources")

    private fun parseResources(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resourcesDir, path))
        val entries = mutableMapOf<String, String>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            entries[node.getAttribute("name")] = node.tagName
        }
        return entries
    }

    @Test
    fun japaneseLocaleTranslatesEveryDefaultLocaleKey() {
        val en = parseResources("values/strings.xml")
        val ja = parseResources("values-ja/strings.xml")

        assertEquals(en.keys, ja.keys, "values-ja/strings.xml is missing or has extra keys compared to values/strings.xml")
        for (key in en.keys) {
            assertEquals(en.getValue(key), ja.getValue(key), "\"$key\" is a <${en[key]}> in the default locale but a <${ja[key]}> in Japanese")
        }
    }

    @Test
    fun everyDefaultLocalePluralHasBothOneAndOtherQuantities() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resourcesDir, "values/strings.xml"))
        val plurals = doc.documentElement.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val plural = plurals.item(i) as Element
            val quantities = plural.getElementsByTagName("item").let { items ->
                (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
            }
            assertTrue(
                "one" in quantities && "other" in quantities,
                "<plurals name=\"${plural.getAttribute("name")}\"> in values/strings.xml must have both " +
                    "\"one\" and \"other\" quantities, found $quantities",
            )
        }
    }
}
