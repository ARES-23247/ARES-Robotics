package com.ares.analytics.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StudioDependencyBoundaryTest {
    @Test fun `services and domain do not depend on presentation`() {
        val root = listOf(File("src/main/kotlin/com/ares/analytics"), File("app/src/main/kotlin/com/ares/analytics"))
            .first { it.isDirectory }
        val violations = mutableListOf<String>()
        for (area in listOf("service", "domain")) {
            val sources = File(root, area).walkTopDown().filter { it.extension == "kt" }.toList()
            assertTrue(sources.isNotEmpty(), "Missing $area sources")
            for (source in sources) {
                val forbidden = if (area == "domain") listOf("ui", "viewmodel", "service") else listOf("ui", "viewmodel")
                source.readLines().forEachIndexed { index, line ->
                    if (forbidden.any { line.trimStart().startsWith("import com.ares.analytics.$it.") } ||
                        (area == "domain" && line.trimStart().startsWith("import androidx.compose."))) {
                        violations += "${source.relativeTo(root)}:${index + 1}: $line"
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }
}
