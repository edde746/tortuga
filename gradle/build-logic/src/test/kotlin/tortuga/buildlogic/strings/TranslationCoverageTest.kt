package tortuga.buildlogic.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every translation in the repository's `shared/i18n/` has every English string. A key a translation lacks (or leaves
 * empty) shows in English at run time, so nothing else flags it; this lists them per language.
 */
class TranslationCoverageTest {
    @Test
    fun everyTranslationHasEveryEnglishString() {
        val dir = File(System.getProperty("tortuga.i18n"))
        val sources = dir.listFiles { file -> file.name.endsWith(SOURCE_SUFFIX) }!!
            .map { StringsSource("shared/i18n/${it.name}", it.name, it.readBytes()) }
        val catalog = CatalogReader.read(sources)
        val missing = catalog.locales.drop(1)
            .associate { it.tag to untranslated(catalog.base.root, it.root, path = "") }
            .filterValues { it.isNotEmpty() }
        val report = missing.entries.joinToString("\n") { (tag, keys) -> "$tag$SOURCE_SUFFIX lacks ${keys.size}: ${keys.joinToString()}" }
        assertTrue(missing.isEmpty(), "English strings a translation lacks, which show in English there:\n$report")
    }

    /** The paths of [english]'s strings and plurals that [translation] doesn't have. */
    private fun untranslated(english: Node.Group, translation: Node.Group?, path: String): List<String> =
        english.children.flatMap { (key, node) ->
            val keyPath = if (path.isEmpty()) key else "$path.$key"
            val translated = translation?.children?.get(key)
            when {
                node is Node.Group -> untranslated(node, translated as Node.Group?, keyPath)
                translated == null -> listOf(keyPath)
                else -> emptyList()
            }
        }
}
