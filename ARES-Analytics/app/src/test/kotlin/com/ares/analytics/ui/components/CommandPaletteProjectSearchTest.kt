package com.ares.analytics.ui.components

import com.ares.analytics.domain.navigation.NavigationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPaletteProjectSearchTest {

    @Test
    fun `filterCommandPaletteProjectItems returns stage items when query is blank and matches entities by keyword or name`() {
        val items = defaultRobotStudioStageItems() + listOf(
            CommandPaletteProjectItem(
                id = "subsystem:pivot-arm",
                label = "Subsystem: Pivot Arm",
                subtitle = ".ares/subsystems/pivot-arm.aressubsystem · SINGLE_JOINT_ARM",
                category = CommandPaletteProjectCategory.SUBSYSTEM,
                target = NavigationTarget.SUBSYSTEM_GEN,
                keywords = listOf("pivot-arm", "Pivot Arm", "SINGLE_JOINT_ARM", "subsystem", "mechanism"),
            ),
            CommandPaletteProjectItem(
                id = "superstructure:scoring-coord",
                label = "Superstructure: Scoring Coordinator",
                subtitle = ".ares/superstructures/scoring-coord.aressuperstructure · 4 postures",
                category = CommandPaletteProjectCategory.SUPERSTRUCTURE,
                target = NavigationTarget.SUPERSTRUCTURE_STUDIO,
                keywords = listOf("scoring-coord", "High Basket", "Stow"),
            ),
            CommandPaletteProjectItem(
                id = "routine:four-sample-auto",
                label = "Routine: Four Sample Auto",
                subtitle = ".ares/routines/four-sample-auto.aresroutine · 7 steps",
                category = CommandPaletteProjectCategory.ROUTINE,
                target = NavigationTarget.PATH_PLANNER,
                keywords = listOf("four-sample-auto", "autonomous", "routine"),
            ),
        )

        val blankResults = filterCommandPaletteProjectItems("", items)
        assertEquals(8, blankResults.size)
        assertTrue(blankResults.all { it.category == CommandPaletteProjectCategory.STAGE })

        val pivotResults = filterCommandPaletteProjectItems("pivot", items)
        assertEquals(1, pivotResults.size)
        assertEquals(NavigationTarget.SUBSYSTEM_GEN, pivotResults.single().target)

        val postureResults = filterCommandPaletteProjectItems("High Basket", items)
        assertEquals(1, postureResults.size)
        assertEquals(NavigationTarget.SUPERSTRUCTURE_STUDIO, postureResults.single().target)

        val routineResults = filterCommandPaletteProjectItems("four sample", items)
        assertEquals(1, routineResults.size)
        assertEquals(NavigationTarget.PATH_PLANNER, routineResults.single().target)
    }
}
