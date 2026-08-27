package com.areslib.routine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutonomousCatalogTest {
    @Test
    fun `catalog separates selector metadata from reusable routine`() {
        val catalog = AutonomousCatalogDocument(
            projectId = "team23247",
            defaultEntryId = "score-left",
            entries = listOf(
                AutonomousCatalogEntry(
                    entryId = "score-right",
                    displayName = "Score right",
                    routineId = "score-and-park",
                    startingPose = RoutinePose(1.0, 2.0, 0.0),
                    sortOrder = 2
                ),
                AutonomousCatalogEntry(
                    entryId = "score-left",
                    displayName = "Score left",
                    routineId = "score-and-park",
                    startingPose = RoutinePose(1.0, -2.0, 0.0),
                    sortOrder = 1
                )
            )
        )

        val decoded = AutonomousCatalogCodec.decode(AutonomousCatalogCodec.encode(catalog))

        assertEquals(listOf("score-left", "score-right"), decoded.entries.map { it.entryId })
        assertTrue(validateAutonomousCatalog(decoded, setOf("score-and-park")).isEmpty())
        assertEquals(AutonomousCatalogCodec.contentHash(catalog), AutonomousCatalogCodec.contentHash(decoded))
    }

    @Test
    fun `unknown or disabled defaults fail closed`() {
        val catalog = AutonomousCatalogDocument(
            projectId = "test",
            defaultEntryId = "disabled",
            entries = listOf(
                AutonomousCatalogEntry(
                    entryId = "disabled",
                    displayName = "Disabled",
                    routineId = "missing",
                    startingPose = RoutinePose(0.0, 0.0, 0.0),
                    enabled = false
                )
            )
        )
        val issues = validateAutonomousCatalog(catalog, setOf("known"))

        assertTrue(issues.any { it.code == "unknown_routine" })
        assertTrue(issues.any { it.code == "disabled_default" })
    }
}
