package org.aresfirst.marvin.robot

import com.areslib.action.RobotAction
import com.areslib.frc.FrcSwerveRobot
import org.aresfirst.marvin.generated.GeneratedAresProject
import org.aresfirst.marvin.marvin.MarvinReducer
import org.aresfirst.marvin.marvin.MarvinState
import org.aresfirst.marvin.marvin.SetFlywheelSpeed
import org.aresfirst.marvin.marvin.SetCowlAngle
import org.aresfirst.marvin.marvin.SetMechanismSafetyInhibit
import org.aresfirst.marvin.marvin.SuperstructureSensorUpdate
import org.aresfirst.marvin.marvin.marvin
import com.areslib.math.coordinate.CoordinateTransformers
import com.areslib.pathing.NamedCommands
import com.areslib.state.Alliance
import com.areslib.state.DriveMode
import com.areslib.state.RobotState
import com.areslib.state.RoutineExecutionStatus
import com.areslib.state.SuperstructureState
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** End-to-end contract for generated assets, capability factories, and FRC native execution. */
class FrcNativeAutoContractTest {
    @BeforeEach
    fun setUp() {
        RobotClock.useMockTime(1_000L)
        NamedCommands.clear()
        FrcAutoCapabilities.register()
    }

    @AfterEach
    fun tearDown() {
        NamedCommands.clear()
        RobotClock.useSystemTime()
    }

    @Test
    fun `generated action keys and source runtime registry are identical`() {
        val declared = FrcAutoCapabilities.descriptors.map { it.key.value }.toSet()
        val registered = NamedCommands.catalog().map { it.key.value }.toSet()

        assertEquals(declared, GeneratedAresProject.knownActionKeys)
        assertEquals(declared, registered)
        assertEquals(setOf("shooter.ready"), GeneratedAresProject.knownConditionKeys)
    }

    @Test
    fun `production chooser contains only match reviewed autonomous entries`() {
        assertEquals(
            listOf("do-nothing"),
            GeneratedAresProject.autonomousEntries.filter { it.enabled }.map { it.entryId }
        )
        assertFalse(GeneratedAresProject.routines.containsKey("sim-drive-and-shoot"))
    }

    @Test
    fun `every generated autonomous entry preflights for both alliances`() {
        assertTrue(GeneratedAresProject.autonomousEntries.isNotEmpty())
        GeneratedAresProject.autonomousEntries.filter { it.enabled }.forEach { entry ->
            Alliance.entries.forEach { alliance ->
                val runner = runner(newRobot(alliance)) { entry.entryId }
                runner.autonomousInit()
                assertFalse(
                    runner.isFaultedForTest,
                    "${entry.entryId} failed $alliance preflight: ${runner.statusForTest}"
                )
            }
        }
    }

    @Test
    fun `red generated start pose reflects across the FRC alliance wall`() {
        val robot = newRobot(Alliance.RED)
        val runner = fixtureRunner(robot) { "sim-drive-and-shoot" }

        runner.autonomousInit()

        val pose = robot.store.state.drive.poseEstimator.estimatedPose
        assertEquals(CoordinateTransformers.FRC_FIELD_LENGTH - 2.0, pose.x, 1e-9)
        assertEquals(2.0, pose.y, 1e-9)
        assertEquals(-Math.PI, pose.heading.radians, 1e-9)
        assertFalse(runner.isFaultedForTest)
    }

    @Test
    fun `selection is locked at autonomous init`() {
        var requested = "sim-drive-and-shoot"
        val runner = fixtureRunner(newRobot(Alliance.BLUE)) { requested }

        runner.autonomousInit()
        requested = "do-nothing"
        runner.autonomousPeriodic()

        assertEquals("sim-drive-and-shoot", runner.selectedAutoForTest)
    }

    @Test
    fun `missing selection runs generated do nothing fallback and completes safely`() {
        val robot = newRobot(Alliance.BLUE)
        robot.store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 3.0,
                yMeters = 4.0,
                headingRadians = 0.75,
                timestampMs = 900L,
                isReset = true
            )
        )
        robot.drive.joystickDrive(2.0, -1.0, 0.5, isFieldCentric = false)
        val runner = runner(robot) { "deleted-auto" }

        runner.autonomousInit()
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.angularVelocityRadiansPerSecond)
        assertEquals(DriveMode.X_BRAKE, robot.store.state.drive.driveMode)
        runner.autonomousPeriodic()

        assertFalse(runner.isFaultedForTest)
        assertTrue(runner.isFinishedForTest)
        assertEquals("do-nothing", runner.selectedAutoForTest)
        assertEquals("Complete", runner.statusForTest)
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.angularVelocityRadiansPerSecond)
        assertEquals(DriveMode.X_BRAKE, robot.store.state.drive.driveMode)
        assertTrue(robot.store.state.drive.isXLock)
        val preservedPose = robot.store.state.drive.poseEstimator.estimatedPose
        assertEquals(3.0, preservedPose.x, 1e-9)
        assertEquals(4.0, preservedPose.y, 1e-9)
        assertEquals(0.75, preservedPose.heading.radians, 1e-9)
        assertFalse(shouldSeedAutonomousPose("do-nothing"))
        assertTrue(shouldSeedAutonomousPose("sim-drive-and-shoot"))
    }

    @Test
    fun `stop cancels active generated routine and zeros outputs`() {
        val robot = newRobot(Alliance.BLUE)
        val runner = fixtureRunner(robot) { "sim-drive-and-shoot" }
        runner.autonomousInit()
        runner.autonomousPeriodic()
        assertFalse(runner.isFinishedForTest)

        runner.stop()

        assertTrue(runner.isFinishedForTest)
        assertEquals("Stopped", runner.statusForTest)
        assertEquals(
            RoutineExecutionStatus.CANCELLED,
            robot.store.state.routineState.lastTerminalExecution?.status
        )
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
        assertFalse(robot.store.state.superstructure.marvin.flywheelActive)
    }

    @Test
    fun `autonomous blocks immediately and publishes reason when mechanism safety is inhibited`() {
        val telemetry = FakeTelemetry()
        val robot = newRobot(Alliance.BLUE, telemetry)
        robot.store.dispatch(SetMechanismSafetyInhibit(true))
        val runner = runner(robot) { "do-nothing" }

        runner.autonomousInit()

        assertTrue(runner.isFaultedForTest)
        assertTrue(runner.isFinishedForTest)
        assertEquals("Blocked", runner.statusForTest)
        assertEquals(
            "Mechanism safety is inhibited; autonomous start is blocked",
            telemetry.strings["ARES/Auto/Error"]
        )
    }

    @Test
    fun `generated readiness condition is fresh aligned and fail closed`() {
        val robot = newRobot(Alliance.BLUE)
        FrcAutoCapabilities.actionShooterPrepare().initialize(robot.store.state).forEach(robot.store::dispatch)
        assertEquals(4_000.0, robot.store.state.superstructure.marvin.flywheel.targetVelocityRpm)
        assertEquals(1.55, robot.store.state.superstructure.marvin.cowl.targetAngleRotations)
        val ready = FrcAutoCapabilities.conditionShooterReady()
        robot.store.dispatch(sensorUpdate(flywheelValid = false, cowlValid = true))
        assertFalse(ready(robot.store.state))

        robot.store.dispatch(sensorUpdate(flywheelValid = true, cowlValid = false))
        assertFalse(ready(robot.store.state))

        robot.store.dispatch(sensorUpdate(flywheelValid = true, cowlValid = true, allMotorsAtTarget = false))
        assertFalse(ready(robot.store.state))

        robot.store.dispatch(sensorUpdate(flywheelValid = true, cowlValid = true))
        assertTrue(ready(robot.store.state))

        val feedTask = FrcAutoCapabilities.actionShooterFeedWhenReady()
        // A consumed teleop trigger must not block the auto task: initialize re-arms the latch.
        robot.store.dispatch(org.aresfirst.marvin.marvin.CompleteTransfer())
        assertTrue(robot.store.state.superstructure.marvin.transferConsumedForTrigger)
        feedTask.initialize(robot.store.state).forEach(robot.store::dispatch)
        assertFalse(robot.store.state.superstructure.marvin.transferConsumedForTrigger)

        feedTask.execute(robot.store.state, 20L).forEach(robot.store::dispatch)
        assertFalse(feedTask.isCompleted(robot.store.state, 20L))
        assertTrue(robot.store.state.superstructure.marvin.transferActive)
        assertFalse(feedTask.isCompleted(robot.store.state, 469L))
        assertTrue(feedTask.isCompleted(robot.store.state, 470L))
        feedTask.end(robot.store.state, interrupted = false).forEach(robot.store::dispatch)
        assertFalse(robot.store.state.superstructure.marvin.transferActive)
        // The transfer closed through the bounded lifecycle: the one-shot trigger stays consumed.
        assertTrue(robot.store.state.superstructure.marvin.transferConsumedForTrigger)
        assertEquals(0.0, robot.store.state.superstructure.marvin.feeder.targetVelocityRps)
        assertEquals(0.0, robot.store.state.superstructure.marvin.floor.targetVelocityRps)
        assertNotNull(GeneratedAresProject.runtimeBindings(FrcAutoCapabilities))
    }

    private fun sensorUpdate(
        flywheelValid: Boolean,
        cowlValid: Boolean,
        allMotorsAtTarget: Boolean = flywheelValid
    ) = SuperstructureSensorUpdate(
        flywheelRpm = 4_000.0,
        cowlAngleRotations = 1.55,
        intakeAngle = 0.0,
        pieceDetected = false,
        flywheelVelocityValid = flywheelValid,
        flywheelAllMotorsAtTarget = allMotorsAtTarget,
        cowlAngleValid = cowlValid
    )

    private fun newRobot(
        alliance: Alliance,
        telemetry: ITelemetry = FakeTelemetry()
    ): FrcSwerveRobot = FrcSwerveRobot(
        isSimulation = true,
        baseTelemetry = telemetry,
        initialState = RobotState(
            superstructure = SuperstructureState(custom = MarvinState())
        ),
        reducer = MarvinReducer::reduce
    ).also { robot -> robot.store.dispatch(RobotAction.SetAlliance(alliance)) }

    private fun runner(robot: FrcSwerveRobot, selection: () -> String) =
        FRCAutoOrchestrator(robot = robot, selectionProvider = selection)

    private fun fixtureRunner(robot: FrcSwerveRobot, selection: () -> String): FRCAutoOrchestrator {
        val catalog = AutonomousCatalogCodec.decode(resourceText("ares/autonomous-catalog.json"))
        val routine = AresRoutineCodec.decode(resourceText("ares/routines/sim-drive-and-shoot.aresroutine"))
        return FRCAutoOrchestrator(
            robot = robot,
            selectionProvider = selection,
            autonomousEntries = catalog.entries,
            defaultAutonomousEntryId = catalog.defaultEntryId,
            routineDocuments = mapOf(routine.documentId to routine)
        )
    }

    private fun resourceText(path: String): String = requireNotNull(
        javaClass.classLoader.getResource(path)
    ) { "Missing test resource $path" }.readText()

    private class FakeTelemetry : ITelemetry {
        val strings = mutableMapOf<String, String>()
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
        override fun getString(key: String, defaultValue: String): String = strings[key] ?: defaultValue
    }
}
