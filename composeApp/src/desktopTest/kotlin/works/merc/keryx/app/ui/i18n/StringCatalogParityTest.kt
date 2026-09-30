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

    private fun localization(key: String, locale: String): JsonObject =
        strings.getValue(key).jsonObject.getValue("localizations").jsonObject.getValue(locale).jsonObject

    private fun JsonObject.unitValue(): String = getValue("stringUnit").jsonObject.getValue("value").jsonPrimitive.content

    /** The plural forms of a substitution-style localization, or null for any other shape. */
    private fun substitutionForms(localization: JsonObject): Collection<JsonObject>? =
        localization["substitutions"]?.jsonObject?.values?.single()?.jsonObject
            ?.getValue("variations")?.jsonObject?.getValue("plural")?.jsonObject?.values?.map { it.jsonObject }

    /** Every value of [key] in [locale], whether a plain string or plural variations. */
    private fun catalogValues(key: String, locale: String): List<String> {
        val localization = localization(key, locale)
        substitutionForms(localization)?.let { forms -> return forms.map { it.unitValue() } }
        localization["stringUnit"]?.let { return listOf(it.jsonObject.getValue("value").jsonPrimitive.content) }
        val plural = localization.getValue("variations").jsonObject.getValue("plural").jsonObject
        return plural.values.map { it.jsonObject.unitValue() }
    }

    /** Positions of a value's placeholders; `%arg` inside a substitution stands for [argNum]. */
    private fun positions(value: String, argNum: String?): List<String> =
        placeholder.findAll(value).map { it.groupValues[1] }.toList() +
            if (argNum != null && "%arg" in value) listOf(argNum) else emptyList()

    private val placeholder = Regex("""%(\d+)\$""")

    @Test
    fun englishIsTheSourceLanguage() {
        assertEquals("en", catalog.getValue("sourceLanguage").jsonPrimitive.content)
    }

    @Test
    fun theCatalogCarriesExactlyTheResourceKeys() {
        assertEquals(resources("values").keys, strings.keys)
    }

    /**
     * Apple warns that it "cannot reliably infer argument number for plural variation" when a
     * plain plural variation uses several arguments, so those must be explicit substitutions.
     */
    @Test
    fun aMultiArgumentPluralIsASubstitutionNotAPlainVariation() {
        for ((locale, dir) in listOf("ja" to "values-ja", "en" to "values")) {
            for ((key, texts) in resources(dir)) {
                val localization = localization(key, locale)
                val multiArgument = texts.flatMap { t -> placeholder.findAll(t).map { it.groupValues[1] } }.toSet().size > 1
                if ("variations" !in localization && "substitutions" !in localization) continue // a plain string
                if (multiArgument) {
                    assertTrue("substitutions" in localization, "$locale/$key: needs a substitution")
                    assertTrue("variations" !in localization, "$locale/$key: must not have top-level plural variations")
                } else {
                    assertTrue("substitutions" !in localization, "$locale/$key: a single-argument plural needs no substitution")
                }
            }
        }
    }

    @Test
    fun everyKeyIsTranslatedInBothLocalesWithTheSamePlaceholders() {
        for ((locale, dir) in listOf("ja" to "values-ja", "en" to "values")) {
            for ((key, texts) in resources(dir)) {
                val values = catalogValues(key, locale)
                assertEquals(texts.size, values.size, "$locale/$key: plural forms")
                val expected = texts.flatMap { t -> placeholder.findAll(t).map { it.groupValues[1] } }.toSet()
                val argNum = localization(key, locale)["substitutions"]?.jsonObject?.values?.single()
                    ?.jsonObject?.getValue("argNum")?.jsonPrimitive?.content
                val actual = values.flatMap { v -> positions(v, argNum) }.toSet()
                assertEquals(expected, actual, "$locale/$key: placeholder positions")
                for (value in values) {
                    assertTrue(Regex("""%\d+\$[sd]""").find(value) == null, "$locale/$key still has an Android placeholder: $value")
                    assertTrue("\\n" !in value, "$locale/$key still has an escaped newline: $value")
                }
            }
        }
    }
}
