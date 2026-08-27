package com.ares.analytics.ui.components.routine

import com.ares.analytics.viewmodel.PathPlannerState
import com.areslib.routine.RoutineStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineReplacementPolicyTest {
    @Test
    fun `stale typed references stay visible and actionable`() {
        assertTrue(routineReferenceIsMissing("Intake.Start", null))
        assertEquals(
            "Missing: Intake.Start",
            routineReferenceLabel("Intake.Start", null, true, "No actions", "Choose action"),
        )
        assertEquals(
            "Start intake",
            routineReferenceLabel("Intake.Start", "Start intake", true, "No actions", "Choose action"),
        )
        assertEquals(
            "No actions",
            routineReferenceLabel(null, null, false, "No actions", "Choose action"),
        )
    }
    @Test
    fun `guided setup may replace untouched New shell without a false data-loss warning`() {
        val untouchedShell = PathPlannerState(routineDirty = true)

        assertFalse(shouldConfirmRoutineReplacement(untouchedShell))
    }

    @Test
    fun `any meaningful student edit still requires confirmation`() {
        val renamed = PathPlannerState(
            routineDirty = true,
            routine = PathPlannerState().routine.copy(name = "My auto"),
        )
        val withStep = PathPlannerState(
            routineDirty = true,
            routine = PathPlannerState().routine.copy(steps = listOf(RoutineStep.wait(0.5))),
        )

        assertTrue(shouldConfirmRoutineReplacement(renamed))
        assertTrue(shouldConfirmRoutineReplacement(withStep))
    }

    @Test
    fun `clean routine never prompts even when it has content`() {
        val clean = PathPlannerState(
            routineDirty = false,
            routine = PathPlannerState().routine.copy(name = "Saved routine", steps = listOf(RoutineStep.wait(1.0))),
        )

        assertFalse(shouldConfirmRoutineReplacement(clean))
    }
}
