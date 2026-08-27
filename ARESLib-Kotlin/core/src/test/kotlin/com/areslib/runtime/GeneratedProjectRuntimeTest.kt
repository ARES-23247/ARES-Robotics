package com.areslib.runtime

import com.areslib.routine.RoutineRuntimeBindings
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedProjectRuntimeTest {
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
            createControllerRuntimes = { scheme, _, _, _ ->
                require(scheme == null)
                emptyMap()
            },
            emitDriveCommand = { driveEmissions++ },
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
            createControllerRuntimes = { _, _, _, _ ->
                mapOf(2 to com.areslib.input.ControllerBindingRuntime())
            },
            emitDriveCommand = {},
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
