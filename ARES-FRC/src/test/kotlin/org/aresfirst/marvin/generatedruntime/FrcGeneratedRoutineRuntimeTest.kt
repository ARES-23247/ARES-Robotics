package org.aresfirst.marvin.generatedruntime

import com.areslib.routine.AutonomousCatalogResolver
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.math.wrapAngle
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutinePose
import com.areslib.state.Alliance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrcGeneratedRoutineRuntimeTest {
    @Test
    fun `selector ignores disabled choices and deterministically falls back`() {
        val selector = AutonomousCatalogResolver(
            entries = listOf(
                entry("score", order = 1),
                entry("do-nothing", order = 0),
                entry("disabled", order = -1, enabled = false)
            ),
            defaultEntryId = "disabled"
        )

        assertEquals(listOf("do-nothing", "score"), selector.availableEntryIds)
        val missing = selector.resolve("disabled")
        assertEquals("do-nothing", missing.entry.entryId)
        assertTrue(missing.usedFallback)

        val score = selector.resolve("score")
        assertEquals("score", score.entry.entryId)
        assertFalse(score.usedFallback)
    }

    @Test
    fun `opposite alliance transform mirrors only the FRC field X axis`() {
        val source = RoutinePose(2.0, 3.0, 0.4)
        val mirrored = FrcRoutinePoseTransform.apply(
            source,
            authoredAlliance = RoutineAlliance.BLUE,
            activeAlliance = Alliance.RED,
            mirrorForOppositeAlliance = true
        )

        assertEquals(CoordinateTransformers.FRC_FIELD_LENGTH - 2.0, mirrored.x, 1e-9)
        assertEquals(3.0, mirrored.y, 1e-9)
        assertEquals(wrapAngle(Math.PI - 0.4), mirrored.heading.radians, 1e-9)

        val unchanged = FrcRoutinePoseTransform.apply(
            source,
            authoredAlliance = RoutineAlliance.RED,
            activeAlliance = Alliance.RED,
            mirrorForOppositeAlliance = true
        )
        assertEquals(2.0, unchanged.x, 1e-9)
        assertEquals(3.0, unchanged.y, 1e-9)
        assertEquals(0.4, unchanged.heading.radians, 1e-9)
    }

    private fun entry(id: String, order: Int, enabled: Boolean = true) = AutonomousCatalogEntry(
        entryId = id,
        displayName = id,
        routineId = "do-nothing",
        startingPose = RoutinePose(1.0, 1.0, 0.0),
        sortOrder = order,
        enabled = enabled
    )
}
