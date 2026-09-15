package com.ares.analytics.viewmodel

import com.ares.analytics.shared.models.League
import com.areslib.routine.RoutineStepKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RoutinePreviewRecoveryAuditTest {
    @Test fun `invalid edit clears playback and later valid edits recover without escaped errors`() = runBlocking {
        val owner = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(owner + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val model = PathPlannerViewModel(scope)
        suspend fun await(predicate: (PathPlannerState) -> Boolean): PathPlannerState =
            withTimeout(5_000) { model.state.first(predicate) }
        try {
            model.onIntent(PathPlannerIntent.CreateRoutine("Preview recovery"))
            await { it.routine.name == "Preview recovery" }
            model.onIntent(PathPlannerIntent.SetAutonomousAvailability(true, League.FTC))
            await { it.autonomousEntry != null }
            model.onIntent(PathPlannerIntent.AddRoutineStep(RoutineStepKind.WAIT))
            val initial = await { it.trajectory != null && it.routine.steps.size == 1 }
            val wait = initial.routine.steps.single()
            model.onIntent(PathPlannerIntent.TogglePlayback)
            await { it.isPlaying }

            model.onIntent(PathPlannerIntent.UpdateRoutineStep(wait.stepId, wait.copy(durationSeconds = Double.NaN)))
            val invalid = await { it.routinePreviewWarning != null }
            assertNull(invalid.trajectory)
            assertEquals(0.0, invalid.estimatedDuration)
            assertEquals(0.0, invalid.playbackTime)
            assertFalse(invalid.isPlaying)
            assertTrue(invalid.previewActions.isEmpty())

            model.onIntent(PathPlannerIntent.UpdateRoutineStep(wait.stepId, wait.copy(durationSeconds = 0.75)))
            val recovered = await { it.trajectory != null && it.estimatedDuration == 0.75 }
            assertNull(recovered.routinePreviewWarning)
            assertFalse(recovered.isPlaying)
            assertEquals(listOf(0.0, 0.75), recovered.trajectory!!.states.map { it.timeSeconds })
            assertTrue(failures.isEmpty(), "Preview exception escaped: $failures")
        } finally {
            owner.cancelAndJoin()
        }
    }
}
