package com.areslib.auto

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.CommandKey
import com.areslib.pathing.DriveModel
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.NamedCommandDescriptor
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.TrajectoryEngine
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPlanner
import com.areslib.pathing.TrajectoryPreset
import com.areslib.sequencer.StateActionTask
import com.areslib.sequencer.TaskExecutor
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Acceptance contract for an auto after it leaves the visual editor.
 *
 * This deliberately crosses codec, validation, trajectory generation, named-command resolution,
 * task compilation, and deterministic execution. Platform repositories separately verify that
 * their deployed files and advertised command catalogs satisfy this shared contract.
 */
class AutoAuthoringRuntimeAcceptanceTest {
    private val intakeStop = CommandKey("intake.stop")
    private val lightsGreen = CommandKey("lights.green")

    @BeforeEach
    fun registerCapabilities() {
        NamedCommands.clear()
        NamedCommands.register(descriptor(intakeStop, "Stop intake")) {
            StateActionTask("Stop intake") { state ->
                RobotAction.SetAlliance(Alliance.BLUE, state.timestampMs)
            }
        }
        NamedCommands.register(descriptor(lightsGreen, "Lights green")) {
            StateActionTask("Lights green") { state ->
                RobotAction.SetAlliance(Alliance.RED, state.timestampMs)
            }
        }
        RobotClock.useMockTime(0L)
    }

    @AfterEach
    fun releaseGlobalTestState() {
        NamedCommands.clear()
        RobotClock.useSystemTime()
    }

    @Test
    fun `editor document round trips compiles and executes its timeline`() {
        val authored = autonomous("Student score and park") {
            startAt(0.meters, 0.meters, 0.degrees)
            driveTo(0.5.meters, 0.meters, 0.degrees, TrajectoryPreset.SAFE) {
                onArrival(lightsGreen)
            }
            waitFor(250.milliseconds)
            run(intakeStop)
        }
        val decoded = AresAutoCodec.decode(AresAutoCodec.encode(authored))
        assertEquals(authored, decoded)

        val drivetrain = RecordingDrivetrain()
        val compilation = AutoRoutineCompiler(
            trajectoryPlanner = TrajectoryPlanner(listOf(JerkLimitedTrajectoryProvider)),
            follower = HolonomicPathFollower(drivetrain),
            driveModel = DriveModel.MECANUM,
            limitsForPreset = { limits() }
        ).compile(decoded)

        assertTrue(compilation.isSuccess, compilation.issues.joinToString { it.message })
        assertEquals(listOf(TrajectoryEngine.JERK_LIMITED), compilation.selectedEngines)

        val store = Store()
        val executor = TaskExecutor().apply { addTask(requireNotNull(compilation.task)) }
        val dispatched = mutableListOf<RobotAction>()
        val timestamps = longArrayOf(0L, 20L, 16_000L, 16_001L, 16_260L, 16_261L)
        for (timestamp in timestamps) {
            RobotClock.useMockTime(timestamp)
            val actions = executor.update(store.state, timestamp)
            actions.forEach { action ->
                dispatched += action
                store.dispatch(action)
            }
        }

        assertEquals(0, executor.size, "compiled auto should reach a terminal state")
        assertTrue(dispatched.any { it is RobotAction.SwitchPath })
        assertEquals(Alliance.BLUE, store.state.drive.alliance)
        assertTrue(drivetrain.stopCommands > 0, "path completion must stop drivetrain output")
    }

    private fun descriptor(key: CommandKey, name: String) = NamedCommandDescriptor(
        key = key,
        displayName = name,
        description = "$name during the acceptance auto",
        category = "Acceptance"
    )

    private fun limits() = TrajectoryLimits(
        maxVelocityMps = 1.5,
        maxAccelerationMps2 = 1.5,
        maxJerkMps3 = 6.0,
        maxCentripetalAccelerationMps2 = 1.5,
        maxAngularVelocityRps = 2.0,
        maxAngularAccelerationRps2 = 3.0
    )

    private class RecordingDrivetrain : DrivetrainSubsystem {
        var stopCommands = 0

        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {
            if (vx == 0.0 && vy == 0.0 && omega == 0.0) stopCommands++
        }

        override fun getEstimatedPose(): Pose2d = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
    }
}
