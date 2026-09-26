package works.merc.keryx.app.ui.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Apple app's String Catalog (`generateStringCatalog`, run before this test) must carry exactly
 * the strings.xml keys, in both locales, with every placeholder converted — so the two UIs can't
 * drift apart in which texts exist or how many arguments each takes.
 */
class StringCatalogParityTest {

    private val resourcesDir = File("src/commonMain/composeResources")
    private val catalog: JsonObject =
        Json.parseToJsonElement(File("build/generated/stringCatalog/Localizable.xcstrings").readText()).jsonObject
    private val strings = catalog.getValue("strings").jsonObject

    /** key -> every text of that key (one per plural quantity) in [dir]'s strings.xml. */
    private fun resources(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resourcesDir, "$dir/strings.xml"))
        val children = doc.documentElement.childNodes
        return buildMap {
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                val texts = if (element.tagName == "plurals") {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).map { items.item(it).textContent }
                } else {
                    listOf(element.textContent)
                }
                put(element.getAttribute("name"), texts)
            }
        }
    }

    /** Every value of [key] in [locale], whether a plain string or plural variations. */
    private fun catalogValues(key: String, locale: String): List<String> {
        val localization = strings.getValue(key).jsonObject.getValue("localizations").jsonObject.getValue(locale).jsonObject
        localization["stringUnit"]?.let { return listOf(it.jsonObject.getValue("value").jsonPrimitive.content) }
        val plural = localization.getValue("variations").jsonObject.getValue("plural").jsonObject
        return plural.values.map { it.jsonObject.getValue("stringUnit").jsonObject.getValue("value").jsonPrimitive.content }
    }

    private val placeholder = Regex("""%(\d+)\$""")

    @Test
    fun japaneseIsTheSourceLanguage() {
        assertEquals("ja", catalog.getValue("sourceLanguage").jsonPrimitive.content)
    }

    @Test
    fun theCatalogCarriesExactlyTheResourceKeys() {
        assertEquals(resources("values").keys, strings.keys)
    }

    @Test
    fun everyKeyIsTranslatedInBothLocalesWithTheSamePlaceholders() {
        for ((locale, dir) in listOf("ja" to "values", "en" to "values-en")) {
            for ((key, texts) in resources(dir)) {
                val values = catalogValues(key, locale)
                assertEquals(texts.size, values.size, "$locale/$key: plural forms")
                val expected = texts.flatMap { t -> placeholder.findAll(t).map { it.groupValues[1] } }.toSet()
                val actual = values.flatMap { v -> placeholder.findAll(v).map { it.groupValues[1] } }.toSet()
                assertEquals(expected, actual, "$locale/$key: placeholder positions")
                for (value in values) {
                    assertTrue(Regex("""%\d+\$[sd]""").find(value) == null, "$locale/$key still has an Android placeholder: $value")
                    assertTrue("\\n" !in value, "$locale/$key still has an escaped newline: $value")
                }
            }
        }
    }
}
