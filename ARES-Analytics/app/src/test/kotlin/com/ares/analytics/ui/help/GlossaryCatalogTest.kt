package com.ares.analytics.ui.help

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlossaryCatalogTest {
    @Test
    fun `cross links resolve to real lessons and developer references`() {
        val lessonIds = LearningCatalog.lessons.map { it.id }.toSet()
        val referenceIds = DeveloperReferenceCatalog.entries.map { it.id }.toSet()
        GlossaryCatalog.terms.forEach { entry ->
            entry.relatedLessonIds.forEach { lessonId ->
                assertTrue(lessonId in lessonIds, "${entry.term} links unknown lesson '$lessonId'")
            }
            entry.relatedDeveloperReferenceIds.forEach { referenceId ->
                assertTrue(referenceId in referenceIds, "${entry.term} links unknown reference '$referenceId'")
            }
        }
    }

    @Test
    fun `every term cross links unless explicitly awaiting its owning lesson`() {
        GlossaryCatalog.terms.forEach { entry ->
            val linked = entry.relatedLessonIds.isNotEmpty() || entry.relatedDeveloperReferenceIds.isNotEmpty()
            assertTrue(
                linked || entry.term in GlossaryCatalog.unlinkableTerms,
                "${entry.term} has no cross-link and is not in the allowlist",
            )
        }
        GlossaryCatalog.unlinkableTerms.forEach { termName ->
            assertNotNull(GlossaryCatalog.term(termName), "Allowlist names missing term '$termName'")
        }
    }

    @Test
    fun `search matches terms and definition text`() {
        assertEquals(GlossaryCatalog.terms.size, GlossaryCatalog.search("").size)
        assertTrue(GlossaryCatalog.search("ccw").any { it.term == "CCW-positive" })
        assertTrue(GlossaryCatalog.search("networktables").any { it.term.startsWith("NT4") })
        assertTrue(GlossaryCatalog.search("counter-clockwise").any { it.term == "CCW-positive" })
        assertEquals(emptyList(), GlossaryCatalog.search("zzz-no-such-term"))
    }

    @Test
    fun `catalog terms match the authoritative markdown glossary`() {
        val markdown = findGlossaryMarkdown() ?: error(
            "docs/learn/GLOSSARY.md not found above ${System.getProperty("user.dir")}; " +
                "the markdown glossary stays authoritative and must ship with the repo",
        )
        val termRegex = Regex("^\\*\\*(.+?)\\*\\*\\s*$")
        val markdownTerms = markdown.readLines()
            .mapNotNull { line -> termRegex.find(line.trim())?.groupValues?.get(1) }
            .filter { it != "Term" } // the comparison-table header cell
            .toSet()
        val catalogTerms = GlossaryCatalog.terms.map { it.term }.toSet()

        assertEquals(
            markdownTerms,
            catalogTerms,
            "Glossary term sets drifted between docs/learn/GLOSSARY.md and GlossaryCatalog. " +
                "Update both in the same change; the markdown stays authoritative.",
        )
    }

    private fun findGlossaryMarkdown(): File? =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "docs/learn/GLOSSARY.md") }
            .firstOrNull(File::isFile)
}
