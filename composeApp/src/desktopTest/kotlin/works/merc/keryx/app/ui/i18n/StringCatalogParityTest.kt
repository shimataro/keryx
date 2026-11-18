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
 * drift apart in which texts exist or how many arguments each takes. The `apple_infoplist_*` keys
 * go to the Info.plist catalog (`InfoPlist.xcstrings`) instead, keyed by the Info.plist key each
 * localizes.
 */
class StringCatalogParityTest {

    private val resourcesDir = File("src/commonMain/composeResources")
    private val catalog: JsonObject =
        Json.parseToJsonElement(File("build/generated/stringCatalog/Localizable.xcstrings").readText()).jsonObject
    private val strings = catalog.getValue("strings").jsonObject
    private val infoPlistCatalog: JsonObject =
        Json.parseToJsonElement(File("build/generated/stringCatalog/InfoPlist.xcstrings").readText()).jsonObject
    private val infoPlistStrings = infoPlistCatalog.getValue("strings").jsonObject

    private fun isInfoPlistKey(key: String) = key.startsWith("apple_infoplist_")

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
        (strings[key] ?: infoPlistEntry(key)).jsonObject.getValue("localizations").jsonObject.getValue(locale).jsonObject

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

    /** The Info.plist catalog's entry generated from the strings.xml key [key] (named in its comment). */
    private fun infoPlistEntry(key: String) = infoPlistStrings.values.single {
        it.jsonObject["comment"]?.jsonPrimitive?.content == "Generated from strings.xml key $key"
    }

    @Test
    fun theCatalogCarriesExactlyTheResourceKeys() {
        assertEquals(resources("values").keys.filterNot(::isInfoPlistKey).toSet(), strings.keys)
    }

    @Test
    fun theInfoPlistCatalogCarriesExactlyTheInfoPlistKeys() {
        assertEquals("en", infoPlistCatalog.getValue("sourceLanguage").jsonPrimitive.content)
        val infoPlistKeys = resources("values").keys.filter(::isInfoPlistKey)
        assertTrue(infoPlistKeys.isNotEmpty())
        assertEquals(infoPlistKeys.size, infoPlistStrings.size)
        // Every entry is keyed by an Info.plist key and traces back to its own strings.xml key.
        for (key in infoPlistKeys) infoPlistEntry(key)
        assertTrue(infoPlistStrings.keys.all { it.startsWith("NS") }, infoPlistStrings.keys.toString())
    }

    @Test
    fun theLocalNetworkUsageDescriptionLocalizesItsInfoPlistKey() {
        val entry = infoPlistStrings.getValue("NSLocalNetworkUsageDescription").jsonObject
        assertEquals("Generated from strings.xml key apple_infoplist_local_network_usage", entry.getValue("comment").jsonPrimitive.content)
        for ((locale, dir) in listOf("ja" to "values-ja", "en" to "values")) {
            assertEquals(
                resources(dir).getValue("apple_infoplist_local_network_usage").single(),
                localization("apple_infoplist_local_network_usage", locale).unitValue(),
            )
        }
    }

    /**
     * project.yml's English default for the Info.plist key must be the same text, or a locale the
     * catalog does not cover would show a different wording than English users see.
     */
    @Test
    fun projectYmlCarriesTheEnglishLocalNetworkUsageDescription() {
        val english = resources("values").getValue("apple_infoplist_local_network_usage").single()
        val projectYml = File("../appleApp/project.yml").readText()
        assertTrue("NSLocalNetworkUsageDescription: \"$english\"" in projectYml, "project.yml's NSLocalNetworkUsageDescription differs")
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
