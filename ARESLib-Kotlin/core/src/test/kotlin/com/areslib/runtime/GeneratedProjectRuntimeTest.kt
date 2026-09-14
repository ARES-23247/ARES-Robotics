package com.areslib.runtime

import com.areslib.routine.RoutineRuntimeBindings
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.action.RobotAction
import com.areslib.input.*
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GeneratedProjectRuntimeTest {
    @Test
    fun `failed binding releases do not skip other ports direct tasks or routines`() {
        val first = IllegalStateException("first port release")
        val second = IllegalArgumentException("second port release")
        val fixture = CancellationFixture(listOf(first, second))
        try {
            fixture.start()
            val failure = assertThrows(IllegalStateException::class.java) { fixture.runtime.cancelAll("disabled") }
            assertSame(first, failure)
            assertTrue(failure.suppressed.any { it === second })
            fixture.assertCancelled()
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun `failed cleanup dispatch does not skip remaining actions or routines`() {
        val first = IllegalStateException("cleanup dispatch")
        val fixture = CancellationFixture(dispatchFailure = first)
        try {
            fixture.start()
            val failure = assertThrows(IllegalStateException::class.java) { fixture.runtime.cancelAll("disabled") }
            assertSame(first, failure)
            fixture.assertCancelled()
        } finally {
            fixture.cleanup()
        }
    }

    private class CancellationFixture(
        releaseFailures: List<Throwable?> = listOf(null, null),
        private val dispatchFailure: Throwable? = null,
    ) {
        private val released = IntArray(2)
        private val ended = mutableListOf<String>()
        private val cleanupActions = listOf(
            RobotAction.RoutineCompleted(101L, "direct-first", 0L),
            RobotAction.RoutineCompleted(102L, "direct-second", 0L),
        )
        private val dispatched = mutableListOf<RobotAction>()
        private fun task(id: String) = object : Task {
            override val name = id
            override fun isCompleted(state: RobotState, elapsedMs: Long) = false
            override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
                super.end(state, interrupted)
                assertTrue(interrupted)
                ended += id
                return if (id == "direct") cleanupActions else emptyList()
            }
        }
        val runtime = GeneratedProjectControlRuntime(
            definition = GeneratedProjectDefinition(
                defaultControlSchemeId = "driver", contentSha256 = "hash", hasGeneratedDriveBindings = false,
                routines = mapOf("active" to RoutineDocument(documentId = "active", name = "Active",
                    steps = listOf(RoutineStep.action("hold")))),
                runtimeBindings = { _: Unit -> RoutineRuntimeBindings(
                    createActionTask = { _, _ -> task("routine") }, createCondition = { _, _ -> null },
                ) },
                createControls = { _, _, _, _ -> GeneratedProjectControls(
                    (0..1).associateWith { port -> ControllerBindingRuntime(
                        digitalBindings = listOf(DigitalBinding(RawButtonSource(0), listener = object : DigitalBindingListener {
                            override fun onRelease(heldForNanos: Long, reason: BindingReleaseReason) {
                                released[port]++
                                releaseFailures[port]?.let { throw it }
                            }
                        })),
                        nanoTime = { 2L },
                    ) },
                ) {} },
            ),
            stateProvider = ::RobotState,
            dispatch = { action ->
                dispatched += action
                if (action === cleanupActions[0]) dispatchFailure?.let { throw it }
            },
            capabilities = Unit, maximumControllerPorts = 2,
        )
        fun start() {
            val frame = InputFrame()
            frame.beginSample(true, reportedButtonCount = 1, sampleTimeNanos = 0L)
            frame.setButton(0, false)
            for (port in 0..1) runtime.updatePort(port, frame, 0L)
            frame.beginSample(true, reportedButtonCount = 1, sampleTimeNanos = 1L)
            frame.setButton(0, true)
            for (port in 0..1) runtime.updatePort(port, frame, 1L)
            runtime.submit("direct", task("direct"))
            runtime.requestRoutine("active")
            runtime.updateTasks()
            assertEquals(1, runtime.routineManager.activeCount)
        }
        fun assertCancelled() {
            assertEquals(listOf(1, 1), released.toList())
            assertEquals(listOf("direct", "routine"), ended)
            assertTrue(dispatched.containsAll(cleanupActions))
            assertEquals(0, runtime.routineManager.activeCount)
            assertEquals(0, runtime.routineManager.queuedCount)
            assertTrue(dispatched.any { it is RobotAction.RoutineCancelled && it.routineId == "active" })
            runtime.cancelAll("repeated stop")
            assertEquals(listOf(1, 1), released.toList())
            assertEquals(listOf("direct", "routine"), ended)
        }
        fun cleanup() {
            // A regressed runtime may stop after each throwing port; release all test resources
            // without replacing the original assertion failure with a cleanup exception.
            repeat(3) { runCatching { runtime.cancelAll("test cleanup") } }
        }
    }

    @Test
    fun `runtime owns generated scheduler state without platform assumptions`() {
        var driveEmissions = 0
        val definition = GeneratedProjectDefinition(
            defaultControlSchemeId = null,
            contentSha256 = "abc123",
            hasGeneratedDriveBindings = false,
            routines = emptyMap(),
            runtimeBindings = { _: Unit ->
                RoutineRuntimeBindings(
                    createActionTask = { _, _ -> null },
                    createCondition = { _, _ -> null },
                )
            },
            createControls = { scheme, _, _, _ ->
                require(scheme == null)
                GeneratedProjectControls(emptyMap()) { driveEmissions++ }
            },
        )
        val runtime = GeneratedProjectControlRuntime(
            definition = definition,
            stateProvider = ::RobotState,
            dispatch = {},
            capabilities = Unit,
            maximumControllerPorts = 2,
        )

        assertFalse(runtime.hasGeneratedDriveBindings)
        assertEquals("hand-authored-only", runtime.controlsSource)
        assertEquals(0, runtime.activeControllerPortCount)
        assertFalse(runtime.hasControllerPort(0))
        assertEquals(2, runtime.controllerPortCapacity)
        runtime.emitDriveCommand()
        runtime.updateTasks()
        assertEquals(1, driveEmissions)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.updatePort(2, com.areslib.input.InputFrame(), 1L)
        }
    }

    @Test
    fun `runtime rejects a generated port outside its league host capacity`() {
        val definition = GeneratedProjectDefinition(
            defaultControlSchemeId = "driver",
            contentSha256 = "hash",
            hasGeneratedDriveBindings = true,
            routines = emptyMap(),
            runtimeBindings = { _: Unit ->
                RoutineRuntimeBindings(
                    createActionTask = { _, _ -> null },
                    createCondition = { _, _ -> null },
                )
            },
            createControls = { _, _, _, _ ->
                GeneratedProjectControls(mapOf(2 to com.areslib.input.ControllerBindingRuntime())) {}
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            GeneratedProjectControlRuntime(
                definition = definition,
                stateProvider = ::RobotState,
                dispatch = {},
                capabilities = Unit,
                maximumControllerPorts = 2,
            )
        }
        assertTrue(definition.hasGeneratedDriveBindings)
    }
}
