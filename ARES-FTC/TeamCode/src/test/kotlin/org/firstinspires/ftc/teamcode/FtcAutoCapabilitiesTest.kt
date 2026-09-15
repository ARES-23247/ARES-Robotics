package org.firstinspires.ftc.teamcode

import com.areslib.pathing.NamedCommands
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.Alliance
import org.firstinspires.ftc.teamcode.dsl.FtcAutoCapabilities
import org.firstinspires.ftc.teamcode.dsl.FtcFieldEnvelope
import org.firstinspires.ftc.teamcode.dsl.validateFtcAutonomousBounds
import org.firstinspires.ftc.teamcode.generated.GeneratedAresProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

class FtcAutoCapabilitiesTest {
    @Before fun clearBefore() = NamedCommands.clear()
    @After fun clearAfter() = NamedCommands.clear()

    @Test
    fun `drive recovery reports failed and successful neutral attempts`() {
        var shouldRecover = false
        FtcAutoCapabilities.registerDriveRecovery { shouldRecover }
        val key = FtcAutoCapabilities.DRIVE_RECOVER_NEUTRAL.key
        val rejected = requireNotNull(NamedCommands.create(key, 0L))
        try {
            rejected.initialize(com.areslib.state.RobotState())
            assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(rejected))
        } finally {
            rejected.reset()
        }

        shouldRecover = true
        val recovered = requireNotNull(NamedCommands.create(key, 1L))
        try {
            recovered.initialize(com.areslib.state.RobotState())
            assertTrue(recovered.isCompleted(com.areslib.state.RobotState(), 0L))
        } finally {
            recovered.reset()
        }
    }

    @Test
    fun `recovery factories are lazy independent and retain exclusive drive ownership`() {
        var calls = 0
        FtcAutoCapabilities.registerDriveRecovery { calls++; true }
        val descriptor = FtcAutoCapabilities.DRIVE_RECOVER_NEUTRAL
        assertEquals(listOf(descriptor), NamedCommands.catalog())
        assertEquals(com.areslib.sequencer.TaskResources.DRIVE, descriptor.requiredResources)
        val first = requireNotNull(NamedCommands.create(descriptor.key, 0L))
        val second = requireNotNull(NamedCommands.create(descriptor.key, 1L))
        val state = com.areslib.state.RobotState()
        try {
            assertNotSame(first, second)
            assertEquals(0, calls)
            assertEquals(descriptor.requiredResources, first.requiredResources)
            assertEquals(descriptor.displayName, first.name)
            assertTrue(first.initialize(state).isEmpty())
            repeat(10) {
                assertTrue(first.isCompleted(state, it.toLong()))
                assertTrue(first.execute(state, it.toLong()).isEmpty())
            }
            assertEquals(1, calls)
            assertFalse(second.isCompleted(state, 0L))
            first.end(state, false)
            first.releaseRuntimeState()
            assertFalse(first.isCompleted(state, 0L))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(first))
            second.initialize(state)
            assertEquals(2, calls)
        } finally {
            first.reset()
            second.reset()
        }
    }

    @Test
    fun `recovery callback failure propagates without successful completion`() {
        val failure = IllegalStateException("neutral write failed")
        FtcAutoCapabilities.registerDriveRecovery { throw failure }
        val task = requireNotNull(NamedCommands.create(FtcAutoCapabilities.DRIVE_RECOVER_NEUTRAL.key, 0L))
        val state = com.areslib.state.RobotState()
        try {
            val observed = runCatching { task.initialize(state) }.exceptionOrNull()
            org.junit.Assert.assertSame(failure, observed)
            assertFalse(task.isCompleted(state, 0L))
        } finally {
            task.reset()
        }
    }

    @Test
    fun `runtime policy matches generated metadata and reuses immutable options`() {
        val options = org.firstinspires.ftc.teamcode.config.AresRuntimePolicy.options
        assertEquals(GeneratedAresProject.RuntimeOptions.FTC_HUB_COMMAND_TRANSPORT, options.hubCommandTransport.name)
        assertEquals(GeneratedAresProject.RuntimeOptions.FTC_LIMELIGHT_PROXY_ENABLED, options.limelightProxyEnabled)
        org.junit.Assert.assertSame(options, org.firstinspires.ftc.teamcode.config.AresRuntimePolicy.options)
    }

    @Test
    fun `preflight rejects unsupported FTC drive preset and engine`() {
        val routine = RoutineDocument(
            documentId = "test",
            name = "test",
            steps = listOf(RoutineStep.driveTo(RoutineDriveStep(
                target = RoutinePose(0.5, 0.0, 0.0),
                motionPresetKey = "warp",
                preferredEngineKey = "engine-that-ftc-does-not-implement",
            ))),
        )
        val errors = preflight("test", routine)
        assertTrue(errors.any { it.contains("Unknown FTC motion preset") })
        assertTrue(errors.any { it.contains("preferred trajectory engine") })
    }

    @Test
    fun `preflight rejects drives that a race or deadline can interrupt`() {
        val drive = RoutineStep.driveTo(RoutineDriveStep(target = RoutinePose(0.5, 0.0, 0.0)))
        val following = RoutineStep.driveTo(RoutineDriveStep(target = RoutinePose(0.8, 0.0, 0.0)))
        val race = RoutineDocument(documentId = "race", name = "race", steps = listOf(RoutineStep.firstToFinish(listOf(RoutineStep.wait(0.1), drive)), following))
        val deadline = RoutineDocument(documentId = "deadline", name = "deadline", steps = listOf(RoutineStep.deadline(RoutineStep.wait(0.1), listOf(drive)), following))
        assertTrue(preflight("race", race).any { it.contains("indeterminate pose") })
        assertTrue(preflight("deadline", deadline).any { it.contains("interrupted by its deadline") })
    }

    @Test
    fun `preflight accepts generated subsystem actions without NamedCommands registration`() {
        val generatedAction = GeneratedAresProject.knownActionKeys
            .first { it.startsWith("subsystem.") }
        val routine = RoutineDocument(
            documentId = "generated-action",
            name = "generated action",
            steps = listOf(RoutineStep.action(generatedAction)),
        )

        assertTrue(preflight("generated-action", routine).isEmpty())
    }

    @Test
    fun `preflight accepts generated Light Practice routine`() {
        val entry = GeneratedAresProject.autonomousEntries.first { it.entryId == "test-auto" }
        val errors = validateFtcAutonomousBounds(
            entry = entry,
            routines = GeneratedAresProject.routines,
            envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4),
            selectedAlliance = Alliance.RED,
            obstacles = emptyList(),
        )

        assertTrue(errors.joinToString("; "), errors.isEmpty())
    }

    private fun preflight(id: String, routine: RoutineDocument): List<String> = validateFtcAutonomousBounds(
        entry = AutonomousCatalogEntry(entryId = id, displayName = id, routineId = id, startingPose = RoutinePose(0.0, 0.0, 0.0)),
        routines = mapOf(id to routine),
        envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4),
        selectedAlliance = Alliance.RED,
        obstacles = emptyList(),
    )
}
